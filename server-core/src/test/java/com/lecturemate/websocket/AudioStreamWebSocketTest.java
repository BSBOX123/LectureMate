package com.lecturemate.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 오디오 스트림 WebSocket 인증 테스트 (SPEC §2.1-2).
 *
 * <p>정상 경로는 FastAPI 연결이 필요해 여기서 다루지 않는다. 인증 거부는 FastAPI 에 연결하기 전에 일어나므로 검증할 수 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudioStreamWebSocketTest {

  @LocalServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private LectureRepository lectureRepository;
  @Autowired private JwtTokenService jwtTokenService;

  private final StandardWebSocketClient client = new StandardWebSocketClient();
  private Long lectureId;
  private String otherUserToken;

  @BeforeEach
  void setUp() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("ws-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("ws-other@example.com", "hash", "타인"));
    lectureId = lectureRepository.save(new Lecture(owner, "녹음 테스트 강의")).getId();
    otherUserToken = jwtTokenService.issueAccessToken(other, Instant.now());
  }

  @AfterEach
  void tearDown() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
  }

  private WebSocketSession connect(String query) throws Exception {
    URI uri =
        URI.create("ws://localhost:" + port + "/ws/v1/lectures/" + lectureId + "/audio" + query);
    return client.execute(new TextWebSocketHandler(), uri.toString()).get(5, TimeUnit.SECONDS);
  }

  private void assertClosed(WebSocketSession session) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(session.isOpen()).isFalse());
  }

  @Test
  void rejectsConnectionWithoutToken() throws Exception {
    assertClosed(connect(""));
  }

  @Test
  void rejectsInvalidToken() throws Exception {
    assertClosed(connect("?token=not-a-jwt"));
  }

  @Test
  void rejectsTokenOfAnotherUser() throws Exception {
    assertClosed(connect("?token=" + otherUserToken));
  }

  @Test
  void rejectsUnknownLecture() throws Exception {
    String ownerToken =
        jwtTokenService.issueAccessToken(
            userRepository.findByEmail("ws-owner@example.com").orElseThrow(), Instant.now());
    URI uri = URI.create("ws://localhost:" + port + "/ws/v1/lectures/999999/audio?token=" + ownerToken);
    WebSocketSession session =
        client.execute(new TextWebSocketHandler(), uri.toString()).get(5, TimeUnit.SECONDS);
    assertClosed(session);
  }
}
