package com.lecturemate.websocket;

import com.lecturemate.config.AudioProperties;
import com.lecturemate.config.FastApiProperties;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.service.LectureService;
import com.lecturemate.service.StorageService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 브라우저 마이크 오디오 청크 수집 및 실시간 STT 프리뷰 중계 (SPEC §2.1-2, §2.2-5).
 *
 * <p>흐름: 브라우저 --PCM--> Spring Boot --PCM--> FastAPI --TRANSCRIPT_PREVIEW--> Spring Boot -->
 * 브라우저. 받은 PCM 은 동시에 {storage}/audio/{id}.pcm 에 누적했다가 연결 종료 시 WAV 로 만든다.
 */
@Component
public class AudioStreamWebSocketHandler extends BinaryWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(AudioStreamWebSocketHandler.class);
  private static final String SESSION_KEY = "recording";

  private final JwtDecoder jwtDecoder;
  private final LectureService lectureService;
  private final StorageService storageService;
  private final FastApiProperties fastApiProperties;
  private final AudioProperties audioProperties;
  private final StandardWebSocketClient fastApiClient = new StandardWebSocketClient();

  public AudioStreamWebSocketHandler(
      JwtDecoder jwtDecoder,
      LectureService lectureService,
      StorageService storageService,
      FastApiProperties fastApiProperties,
      AudioProperties audioProperties) {
    this.jwtDecoder = jwtDecoder;
    this.lectureService = lectureService;
    this.storageService = storageService;
    this.fastApiProperties = fastApiProperties;
    this.audioProperties = audioProperties;
  }

  /** 녹음 세션 하나의 상태. */
  private record Recording(Long lectureId, OutputStream pcmSink, WebSocketSession aiSession) {}

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Long lectureId = parseLectureId(session);
    Long userId = authenticate(session);
    if (lectureId == null || userId == null) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    try {
      lectureService.findOwned(userId, lectureId); // 소유자가 아니면 예외
    } catch (RuntimeException e) {
      log.warn("녹음 거부: 소유하지 않은 강의 lectureId={} userId={}", lectureId, userId);
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }

    WebSocketSession aiSession =
        fastApiClient
            .execute(new PreviewRelayHandler(session), aiStreamUri(lectureId).toString())
            .get(); // 연결될 때까지 대기
    session.getAttributes().put(SESSION_KEY, new Recording(
        lectureId, storageService.openPcmSink(lectureId), aiSession));
    lectureService.changeStatus(lectureId, LectureStatus.RECORDING);
    log.info("녹음 시작 lectureId={} userId={}", lectureId, userId);
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
      throws Exception {
    Recording recording = recordingOf(session);
    if (recording == null) {
      return;
    }
    byte[] payload = new byte[message.getPayload().remaining()];
    message.getPayload().get(payload);
    recording.pcmSink().write(payload);
    if (recording.aiSession().isOpen()) {
      recording.aiSession().sendMessage(new BinaryMessage(payload));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    Recording recording = recordingOf(session);
    if (recording == null) {
      return;
    }
    closeQuietly(recording);
    Path wav = storageService.finalizeWav(recording.lectureId(), audioProperties.sampleRate());
    lectureService.attachAudio(recording.lectureId(), "/files/audio/" + recording.lectureId() + ".wav");
    log.info("녹음 종료 lectureId={} file={} ({}바이트)", recording.lectureId(), wav, sizeOf(wav));
  }

  private void closeQuietly(Recording recording) {
    try {
      recording.pcmSink().close();
    } catch (IOException e) {
      log.warn("PCM 파일 닫기 실패 lectureId={}", recording.lectureId(), e);
    }
    try {
      if (recording.aiSession().isOpen()) {
        recording.aiSession().close();
      }
    } catch (IOException e) {
      log.warn("FastAPI 세션 닫기 실패 lectureId={}", recording.lectureId(), e);
    }
  }

  private static long sizeOf(Path path) {
    try {
      return java.nio.file.Files.size(path);
    } catch (IOException e) {
      return -1;
    }
  }

  private Recording recordingOf(WebSocketSession session) {
    return (Recording) session.getAttributes().get(SESSION_KEY);
  }

  private URI aiStreamUri(Long lectureId) {
    String base = fastApiProperties.baseUrl().toString().replaceFirst("^http", "ws");
    return URI.create(base + "/ai/v1/lectures/" + lectureId + "/audio-stream");
  }

  /** {@code /ws/v1/lectures/{lectureId}/audio} 에서 강의 ID 추출. */
  private static Long parseLectureId(WebSocketSession session) {
    String path = session.getUri() == null ? "" : session.getUri().getPath();
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if ("lectures".equals(parts[i])) {
        try {
          return Long.valueOf(parts[i + 1]);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /** 브라우저는 WebSocket 에 Authorization 헤더를 붙일 수 없어 쿼리 파라미터로 받는다. */
  private Long authenticate(WebSocketSession session) {
    if (session.getUri() == null) {
      return null;
    }
    Map<String, java.util.List<String>> query =
        UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
    java.util.List<String> tokens = query.get("token");
    if (tokens == null || tokens.isEmpty()) {
      return null;
    }
    try {
      Jwt jwt = jwtDecoder.decode(tokens.getFirst());
      return Long.valueOf(jwt.getSubject());
    } catch (RuntimeException e) {
      log.warn("녹음 거부: 토큰 검증 실패 - {}", e.getMessage());
      return null;
    }
  }

  /** FastAPI 가 보내는 TRANSCRIPT_PREVIEW 를 브라우저로 그대로 중계한다. */
  private static class PreviewRelayHandler extends org.springframework.web.socket.handler
      .TextWebSocketHandler {

    private final WebSocketSession browserSession;

    PreviewRelayHandler(WebSocketSession browserSession) {
      this.browserSession = browserSession;
    }

    @Override
    protected void handleTextMessage(WebSocketSession aiSession, TextMessage message)
        throws Exception {
      if (browserSession.isOpen()) {
        browserSession.sendMessage(new TextMessage(message.getPayload()));
      }
    }
  }
}
