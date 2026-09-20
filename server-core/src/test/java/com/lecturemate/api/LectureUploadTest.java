package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.client.FastApiClient.PdfParseResponse;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 강의 생성 및 PDF 업로드 통합 테스트 (SPEC §2.1-1).
 *
 * <p>FastAPI 호출은 목으로 대체한다. 로컬 postgres 컨테이너는 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LectureUploadTest {

  @TempDir static Path storageDir;

  @DynamicPropertySource
  static void storagePath(DynamicPropertyRegistry registry) {
    registry.add("lecturemate.storage.local-path", () -> storageDir.toString());
  }

  private static final byte[] PDF_BYTES = "%PDF-1.7\n%fake pdf\n".getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private LectureRepository lectureRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private String accessToken;
  private Long userId;

  @BeforeEach
  void setUp() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
    User user = userRepository.save(new User("upload-test@example.com", "hash", "업로더"));
    userId = user.getId();
    accessToken = jwtTokenService.issueAccessToken(user, Instant.now());
    given(fastApiClient.parsePdf(any(), any())).willReturn(new PdfParseResponse(2, "COMPLETED"));
  }

  @AfterEach
  void tearDown() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
  }

  private MockMultipartFile pdfFile() {
    return new MockMultipartFile("file", "lecture.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES);
  }

  @Test
  void uploadsPdfAndTriggersParsing() throws Exception {
    String body =
        mockMvc
            .perform(
                multipart("/api/v1/lectures")
                    .file(pdfFile())
                    .param("title", "컴퓨터 알고리즘 5강")
                    .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("컴퓨터 알고리즘 5강"))
            .andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.lectureId").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Long lectureId = Long.valueOf(body.replaceAll(".*\"lectureId\":(\\d+).*", "$1"));
    assertThat(body).contains("\"pdfUrl\":\"/files/pdf/" + lectureId + ".pdf\"");

    // 파일이 {storage}/pdf/{lectureId}.pdf 로 저장된다
    Path stored = storageDir.resolve("pdf").resolve(lectureId + ".pdf");
    assertThat(Files.readAllBytes(stored)).isEqualTo(PDF_BYTES);

    // 커밋 후 비동기로 FastAPI 파싱을 호출하고 상태가 READY 로 바뀐다
    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(lectureRepository.findById(lectureId).orElseThrow().getStatus())
                    .isEqualTo(LectureStatus.READY));
    org.mockito.Mockito.verify(fastApiClient).parsePdf(eq(lectureId), eq(stored.toString()));
  }

  @Test
  void marksLectureFailedWhenParsingFails() throws Exception {
    willThrow(new IllegalStateException("FastAPI 응답 없음"))
        .given(fastApiClient)
        .parsePdf(any(), any());

    String body =
        mockMvc
            .perform(
                multipart("/api/v1/lectures")
                    .file(pdfFile())
                    .param("title", "실패 강의")
                    .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long lectureId = Long.valueOf(body.replaceAll(".*\"lectureId\":(\\d+).*", "$1"));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(lectureRepository.findById(lectureId).orElseThrow().getStatus())
                    .isEqualTo(LectureStatus.FAILED));
  }

  @Test
  void rejectsNonPdfAndUnauthenticatedUploads() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/lectures")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes()))
                .param("title", "잘못된 파일")
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(multipart("/api/v1/lectures").file(pdfFile()).param("title", "비로그인"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void servesPdfOnlyToOwner() throws Exception {
    String body =
        mockMvc
            .perform(
                multipart("/api/v1/lectures")
                    .file(pdfFile())
                    .param("title", "소유자 확인")
                    .header("Authorization", "Bearer " + accessToken))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long lectureId = Long.valueOf(body.replaceAll(".*\"lectureId\":(\\d+).*", "$1"));

    mockMvc
        .perform(
            get("/files/pdf/{id}.pdf", lectureId).header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF));

    // 다른 사용자의 토큰으로는 404
    User other = userRepository.save(new User("other@example.com", "hash", "다른사람"));
    String otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    mockMvc
        .perform(get("/files/pdf/{id}.pdf", lectureId).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/lectures/{id}", lectureId).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void listsOnlyOwnLectures() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/lectures")
                .file(pdfFile())
                .param("title", "내 강의")
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/lectures").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("내 강의"));

    assertThat(lectureRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).hasSize(1);
  }
}
