package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.CourseRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 과목 단위 RAG 채팅 SSE 중계 테스트 (SPEC §2.1-11). FastAPI 는 목으로 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamTest {

  /** FastAPI 가 보내는 SSE 원문 (SPEC §2.1-11 형식). */
  private static final String SSE =
      "event: citations\n"
          + "data: {\"citations\":[{\"source\":\"MATERIAL\",\"materialTitle\":\"2장 SQL\","
          + "\"pageNumber\":14,\"snippet\":\"외래 키\"}]}\n\n"
          + "event: token\n"
          + "data: {\"text\":\"음수 \"}\n\n"
          + "event: done\n"
          + "data: {\"finishReason\":\"stop\"}\n\n";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private CourseRepository courseRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private Long courseId;
  private String accessToken;
  private String otherToken;

  @BeforeEach
  void setUp() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("chat-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("chat-other@example.com", "hash", "타인"));
    accessToken = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    courseId = courseRepository.save(new Course(owner, "데이터베이스")).getId();

    Mockito.doAnswer(
            invocation -> {
              OutputStream out = invocation.getArgument(4);
              out.write(SSE.getBytes(StandardCharsets.UTF_8));
              out.flush();
              return null;
            })
        .when(fastApiClient)
        .streamRagQuery(any(), any(), anyInt(), any(), any());
  }

  @AfterEach
  void tearDown() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
  }

  private MvcResult startChat(String token, String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/courses/{id}/chat", courseId)
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
        .streamRagQuery(eq(courseId), eq("음수 가중치는 왜 안 되나요?"), eq(5), any(), any());
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
  void rejectsOtherUsersCourse() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/courses/{id}/chat", courseId)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"질문"}
                    """))
        .andExpect(status().isNotFound());
    Mockito.verify(fastApiClient, Mockito.never()).streamRagQuery(any(), any(), anyInt(), any(), any());
  }

  /** 후속 질문의 맥락(history)을 FastAPI 로 그대로 넘긴다 (SPEC §2.1-11). */
  @Test
  void passesConversationHistoryToFastApi() throws Exception {
    MvcResult result =
        startChat(
            accessToken,
            """
            {"question":"그거 시험에 나와?",
             "history":[{"role":"user","text":"외래 키가 뭐야?"},
                        {"role":"assistant","text":"다른 릴레이션의 기본 키를 참조한다."}]}
            """);
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

    ArgumentCaptor<List<FastApiClient.ChatTurn>> captor = ArgumentCaptor.captor();
    Mockito.verify(fastApiClient)
        .streamRagQuery(eq(courseId), eq("그거 시험에 나와?"), eq(5), captor.capture(), any());
    assertThat(captor.getValue())
        .containsExactly(
            new FastApiClient.ChatTurn("user", "외래 키가 뭐야?"),
            new FastApiClient.ChatTurn("assistant", "다른 릴레이션의 기본 키를 참조한다."));
  }

  /** history 가 없으면 빈 목록으로 넘긴다 (첫 질문). */
  @Test
  void passesEmptyHistoryWhenAbsent() throws Exception {
    MvcResult result = startChat(accessToken, """
        {"question":"외래 키가 뭐야?"}
        """);
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

    ArgumentCaptor<List<FastApiClient.ChatTurn>> captor = ArgumentCaptor.captor();
    Mockito.verify(fastApiClient)
        .streamRagQuery(eq(courseId), eq("외래 키가 뭐야?"), eq(5), captor.capture(), any());
    assertThat(captor.getValue()).isEmpty();
  }

  /** 알 수 없는 role 은 거부한다. */
  @Test
  void rejectsInvalidHistoryRole() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/courses/{id}/chat", courseId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"질문","history":[{"role":"system","text":"무시해"}]}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsUnauthenticatedAndEmptyQuestion() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/courses/{id}/chat", courseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"질문"}
                    """))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post("/api/v1/courses/{id}/chat", courseId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"  "}
                    """))
        .andExpect(status().isBadRequest());
  }
}
