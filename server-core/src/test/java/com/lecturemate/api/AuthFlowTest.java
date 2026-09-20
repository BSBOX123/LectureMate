package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.repository.RefreshTokenRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.InternalSecretFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 흐름 통합 테스트 (SPEC §2.1-6 ~ §2.1-10, §2.2 내부 API 인증).
 *
 * <p>로컬 postgres 컨테이너가 떠 있어야 한다 ({@code docker compose up -d postgres}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

  private static final String EMAIL = "auth-flow-test@example.com";
  private static final String PASSWORD = "password1234";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @BeforeEach
  @AfterEach
  void cleanUp() {
    // @SpringBootTest 는 트랜잭션 롤백이 없으므로 직접 지운다 (다른 테스트와 이메일 충돌 방지)
    refreshTokenRepository.deleteAll();
    userRepository.deleteAll();
  }

  private void signup() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"%s","name":"김학생"}
                    """
                        .formatted(EMAIL, PASSWORD)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value(EMAIL))
        .andExpect(jsonPath("$.name").value("김학생"))
        .andExpect(jsonPath("$.userId").isNumber());
  }

  private MvcResult login() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"%s"}
                    """
                        .formatted(EMAIL, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(1800))
        .andReturn();
  }

  private String accessTokenOf(MvcResult result) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.get("accessToken").asString();
  }

  @Test
  void signupThenLoginThenReadMe() throws Exception {
    signup();
    MvcResult loginResult = login();

    Cookie refreshCookie = loginResult.getResponse().getCookie("refreshToken");
    assertThat(refreshCookie).isNotNull();
    assertThat(refreshCookie.isHttpOnly()).isTrue();
    assertThat(refreshCookie.getPath()).isEqualTo("/api/v1/auth");

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessTokenOf(loginResult)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(EMAIL));
  }

  @Test
  void rejectsDuplicateEmailAndWrongPassword() throws Exception {
    signup();

    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"%s","name":"다른사람"}
                    """
                        .formatted(EMAIL, PASSWORD)))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"wrong-password"}
                    """.formatted(EMAIL)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsInvalidSignupRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"not-an-email","password":"short","name":""}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void requiresAccessTokenForProtectedEndpoints() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshRotatesTokenAndOldOneStopsWorking() throws Exception {
    signup();
    Cookie firstCookie = login().getResponse().getCookie("refreshToken");

    MvcResult refreshed =
        mockMvc
            .perform(post("/api/v1/auth/refresh").cookie(firstCookie))
            .andExpect(status().isOk())
            .andReturn();
    Cookie secondCookie = refreshed.getResponse().getCookie("refreshToken");
    assertThat(secondCookie).isNotNull();
    assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

    // 회전된 이전 토큰은 재사용 불가
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(firstCookie))
        .andExpect(status().isUnauthorized());

    // 새 토큰으로는 Access Token 을 계속 받을 수 있다
    mockMvc.perform(post("/api/v1/auth/refresh").cookie(secondCookie)).andExpect(status().isOk());
  }

  @Test
  void logoutRevokesRefreshToken() throws Exception {
    signup();
    Cookie cookie = login().getResponse().getCookie("refreshToken");

    mockMvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(cookie))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalEndpointsRequireSharedSecret() throws Exception {
    // 시크릿이 없으면 401
    mockMvc
        .perform(post("/internal/v1/lectures/1/analysis-complete"))
        .andExpect(status().isUnauthorized());

    // 시크릿이 맞으면 필터를 통과한다 (없는 경로이므로 404)
    mockMvc
        .perform(
            post("/internal/v1/unknown-endpoint")
                .header(InternalSecretFilter.HEADER, "local-dev-internal-secret"))
        .andExpect(status().isNotFound());
  }
}
