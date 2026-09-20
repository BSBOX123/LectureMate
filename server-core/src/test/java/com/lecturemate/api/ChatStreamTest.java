package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** RAG 채팅 SSE 중계 테스트 (SPEC §2.1-5). FastAPI 는 목으로 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamTest {

  /** FastAPI 가 보내는 SSE 원문 (SPEC §2.1-5 형식). */
  private static final String SSE =
      "event: citations\n"
          + "data: {\"citations\":[{\"source\":\"SLIDE\",\"pageNumber\":5,"
          + "\"snippet\":\"다익스트라\",\"startTimeMs\":null}]}\n\n"
          + "event: token\n"
          + "data: {\"text\":\"음수 \"}\n\n"
          + "event: done\n"
          + "data: {\"finishReason\":\"stop\"}\n\n";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private LectureRepository lectureRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private Long lectureId;
  private String accessToken;
  private String otherToken;

  @BeforeEach
  void setUp() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("chat-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("chat-other@example.com", "hash", "타인"));
    accessToken = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    lectureId = lectureRepository.save(new Lecture(owner, "질의응답 강의")).getId();

    Mockito.doAnswer(
            invocation -> {
              OutputStream out = invocation.getArgument(3);
              out.write(SSE.getBytes(StandardCharsets.UTF_8));
              out.flush();
              return null;
            })
        .when(fastApiClient)
        .streamRagQuery(any(), any(), anyInt(), any());
  }

  @AfterEach
  void tearDown() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
  }

  private MvcResult startChat(String token, String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/lectures/{id}/chat", lectureId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  @Test
  void relaysSseStreamFromFastApi() throws Exception {
    MvcResult result = startChat(accessToken, """
        {"question":"음수 가중치는 왜 안 되나요?"}
        """);
    // StreamingResponseBody 는 비동기로 처리되므로 dispatch 결과에서 본문을 읽는다
    MvcResult dispatched =
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();

    // MockMvc 는 charset 이 없으면 Latin-1 로 디코딩하므로 바이트에서 직접 UTF-8 로 읽는다
    String body =
        new String(dispatched.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    assertThat(body).isEqualTo(SSE);
    assertThat(body.indexOf("event: citations"))
        .isLessThan(body.indexOf("event: token")); // 출처가 먼저 온다

    Mockito.verify(fastApiClient)
        .streamRagQuery(eq(lectureId), eq("음수 가중치는 왜 안 되나요?"), eq(5), any());
  }

  @Test
  void handlesRequestAsynchronously() throws Exception {
    MvcResult result = startChat(accessToken, """
        {"question":"질문"}
        """);

    assertThat(result.getRequest().isAsyncStarted()).isTrue();
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
  }

  @Test
  void rejectsOtherUsersLecture() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/chat", lectureId)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"질문"}
                    """))
        .andExpect(status().isNotFound());
    Mockito.verify(fastApiClient, Mockito.never()).streamRagQuery(any(), any(), anyInt(), any());
  }

  @Test
  void rejectsUnauthenticatedAndEmptyQuestion() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/chat", lectureId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"질문"}
                    """))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/chat", lectureId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"  "}
                    """))
        .andExpect(status().isBadRequest());
  }
}
