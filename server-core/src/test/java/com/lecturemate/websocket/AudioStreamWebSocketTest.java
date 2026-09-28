package com.lecturemate.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.client.FastApiClient.TranscribeResponse;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.RecordingStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.CourseRecordingRepository;
import com.lecturemate.repository.CourseRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 오디오 스트림 WebSocket 테스트 (SPEC §2.1-8).
 *
 * <p>녹음 중에는 FastAPI 를 부르지 않으므로(실시간 자막 없음) 정상 경로 전체를 여기서 확인할 수 있다. 녹음이
 * 끝난 뒤 자동으로 호출되는 전사만 목으로 대체한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudioStreamWebSocketTest {

  @TempDir static Path storageDir;

  @DynamicPropertySource
  static void storagePath(DynamicPropertyRegistry registry) {
    registry.add("lecturemate.storage.local-path", () -> storageDir.toString());
  }

  @LocalServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private CourseRepository courseRepository;
  @Autowired private CourseRecordingRepository recordingRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private final StandardWebSocketClient client = new StandardWebSocketClient();
  private Long recordingId;
  private String ownerToken;
  private String otherUserToken;

  @BeforeEach
  void setUp() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("ws-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("ws-other@example.com", "hash", "타인"));
    Course course = courseRepository.save(new Course(owner, "데이터베이스"));
    recordingId = recordingRepository.save(new CourseRecording(course, "10월 2일 수업")).getId();
    ownerToken = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherUserToken = jwtTokenService.issueAccessToken(other, Instant.now());
    given(fastApiClient.transcribe(any(), any(), any()))
        .willReturn(new TranscribeResponse("transcribe_1_20260923", "QUEUED"));
  }

  @AfterEach
  void tearDown() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
  }

  private WebSocketSession connect(Long id, String query) throws Exception {
    URI uri = URI.create("ws://localhost:" + port + "/ws/v1/recordings/" + id + "/audio" + query);
    return client.execute(new TextWebSocketHandler(), uri.toString()).get(5, TimeUnit.SECONDS);
  }

  private void assertClosed(WebSocketSession session) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(session.isOpen()).isFalse());
  }

  @Test
  void rejectsConnectionWithoutToken() throws Exception {
    assertClosed(connect(recordingId, ""));
  }

  @Test
  void rejectsInvalidToken() throws Exception {
    assertClosed(connect(recordingId, "?token=not-a-jwt"));
  }

  @Test
  void rejectsTokenOfAnotherUser() throws Exception {
    assertClosed(connect(recordingId, "?token=" + otherUserToken));
  }

  @Test
  void rejectsUnknownRecording() throws Exception {
    assertClosed(connect(999_999L, "?token=" + ownerToken));
  }

  /**
   * 녹음이 끝나면 WAV 가 만들어지고 전사 대기 상태가 된다.
   *
   * <p>전사를 자동으로 시작하지 않는 것이 핵심이다. 음성 1분당 약 34초가 걸려, 수업 직후 바로
   * 노트북을 덮고 이동하는 상황에서는 끝까지 돌 수 없다 (실사용에서 확인).
   */
  @Test
  void savesWavAndWaitsForUserToStartTranscription() throws Exception {
    WebSocketSession session = connect(recordingId, "?token=" + ownerToken);

    session.sendMessage(new BinaryMessage(new byte[3200]));
    assertThat(session.isOpen()).isTrue();
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(recordingRepository.findById(recordingId).orElseThrow().getStatus())
                    .isEqualTo(RecordingStatus.RECORDING));

    session.close();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              CourseRecording recording = recordingRepository.findById(recordingId).orElseThrow();
              assertThat(recording.getAudioUrl())
                  .isEqualTo("/files/audio/" + recordingId + ".wav");
              // 전사 대기. 사용자가 버튼을 눌러야 시작된다
              assertThat(recording.getStatus()).isEqualTo(RecordingStatus.UPLOADED);
            });

    // 44바이트 WAV 헤더 + 보낸 PCM
    Path wav = storageDir.resolve("audio").resolve(recordingId + ".wav");
    assertThat(java.nio.file.Files.size(wav)).isEqualTo(44 + 3200);
    // 전사를 요청하지 않는다
    org.mockito.Mockito.verify(fastApiClient, org.mockito.Mockito.never())
        .transcribe(any(), any(), any());
  }
}
