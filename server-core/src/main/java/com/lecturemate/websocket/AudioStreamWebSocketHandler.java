package com.lecturemate.websocket;

import com.lecturemate.config.AudioProperties;
import com.lecturemate.service.RecordingService;
import com.lecturemate.service.StorageService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 브라우저 마이크 오디오 청크 수집 (SPEC §2.1-8).
 *
 * <p>받은 PCM 을 {storage}/audio/{recordingId}.pcm 에 누적했다가 연결이 끝나면 WAV 로 만들고 전사를
 * 시작한다.
 *
 * <p>녹음 중에는 어떤 모델도 돌리지 않는다. 예전에는 실시간 자막을 위해 청크마다 Whisper 를 돌렸지만,
 * 60초 음성을 처리하는 데 40초가 걸려 녹음 내내 CPU 를 점유했고(발열) 한국어 인식 품질도 쓸 수
 * 없는 수준이었다. 정밀 전사는 녹음이 끝난 뒤 한 번에 한다.
 */
@Component
public class AudioStreamWebSocketHandler extends BinaryWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(AudioStreamWebSocketHandler.class);
  private static final String SESSION_KEY = "recording";

  private final JwtDecoder jwtDecoder;
  private final RecordingService recordingService;
  private final StorageService storageService;
  private final AudioProperties audioProperties;

  public AudioStreamWebSocketHandler(
      JwtDecoder jwtDecoder,
      RecordingService recordingService,
      StorageService storageService,
      AudioProperties audioProperties) {
    this.jwtDecoder = jwtDecoder;
    this.recordingService = recordingService;
    this.storageService = storageService;
    this.audioProperties = audioProperties;
  }

  /** 녹음 세션 하나의 상태. */
  private record Recording(Long recordingId, OutputStream pcmSink) {}

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Long recordingId = parseRecordingId(session);
    Long userId = authenticate(session);
    if (recordingId == null || userId == null) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    try {
      recordingService.requireOwnedById(userId, recordingId); // 소유자가 아니면 예외
    } catch (RuntimeException e) {
      log.warn("녹음 거부: 소유하지 않은 녹음 recordingId={} userId={}", recordingId, userId);
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }

    session
        .getAttributes()
        .put(SESSION_KEY, new Recording(recordingId, storageService.openPcmSink(recordingId)));
    recordingService.markRecording(recordingId);
    log.info("녹음 시작 recordingId={} userId={}", recordingId, userId);
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
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    Recording recording = recordingOf(session);
    if (recording == null) {
      return;
    }
    closeQuietly(recording);
    // WAV 를 먼저 만든 뒤 전사를 요청한다. 순서가 바뀌면 FastAPI 가 없는 파일을 읽는다.
    Path wav = storageService.finalizeWav(recording.recordingId(), audioProperties.sampleRate());
    recordingService.finishRecording(
        recording.recordingId(), "/files/audio/" + recording.recordingId() + ".wav");
    log.info("녹음 종료 recordingId={} file={} ({}바이트)", recording.recordingId(), wav, sizeOf(wav));
  }

  private void closeQuietly(Recording recording) {
    try {
      recording.pcmSink().close();
    } catch (IOException e) {
      log.warn("PCM 파일 닫기 실패 recordingId={}", recording.recordingId(), e);
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

  /** {@code /ws/v1/recordings/{recordingId}/audio} 에서 녹음 ID 추출. */
  private static Long parseRecordingId(WebSocketSession session) {
    String path = session.getUri() == null ? "" : session.getUri().getPath();
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if ("recordings".equals(parts[i])) {
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
    Map<String, List<String>> query =
        UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
    List<String> tokens = query.get("token");
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
}
