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
import com.lecturemate.client.FastApiClient.MaterialParseResponse;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.MaterialStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.CourseMaterialRepository;
import com.lecturemate.repository.CourseRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 과목에 PDF 자료를 올리는 통합 테스트 (SPEC §2.1-4 ~ §2.1-6).
 *
 * <p>FastAPI 호출은 목으로 대체한다. 로컬 postgres 컨테이너는 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialUploadTest {

  @TempDir static Path storageDir;

  @DynamicPropertySource
  static void storagePath(DynamicPropertyRegistry registry) {
    registry.add("lecturemate.storage.local-path", () -> storageDir.toString());
  }

  private static final byte[] PDF_BYTES = "%PDF-1.7\n%fake pdf\n".getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private CourseRepository courseRepository;
  @Autowired private CourseMaterialRepository materialRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private String accessToken;
  private Long courseId;

  @BeforeEach
  void setUp() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
    User user = userRepository.save(new User("upload-test@example.com", "hash", "업로더"));
    courseId = courseRepository.save(new Course(user, "데이터베이스")).getId();
    accessToken = jwtTokenService.issueAccessToken(user, Instant.now());
    given(fastApiClient.parseMaterial(any(), any(), any()))
        .willReturn(new MaterialParseResponse(2, "COMPLETED"));
  }

  @AfterEach
  void tearDown() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
  }

  private MockMultipartFile pdfFile() {
    return new MockMultipartFile("file", "material.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES);
  }

  private Long upload(String title, String token) throws Exception {
    String body =
        mockMvc
            .perform(
                multipart("/api/v1/courses/{courseId}/materials", courseId)
                    .file(pdfFile())
                    .param("title", title)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.valueOf(body.replaceAll(".*\"materialId\":(\\d+).*", "$1"));
  }

  @Test
  void uploadsPdfAndTriggersParsing() throws Exception {
    String body =
        mockMvc
            .perform(
                multipart("/api/v1/courses/{courseId}/materials", courseId)
                    .file(pdfFile())
                    .param("title", "2장 관계형 모델")
                    .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("2장 관계형 모델"))
            .andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.materialId").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Long materialId = Long.valueOf(body.replaceAll(".*\"materialId\":(\\d+).*", "$1"));
    assertThat(body).contains("\"pdfUrl\":\"/files/pdf/" + materialId + ".pdf\"");

    // 파일이 {storage}/pdf/{materialId}.pdf 로 저장된다
    Path stored = storageDir.resolve("pdf").resolve(materialId + ".pdf");
    assertThat(Files.readAllBytes(stored)).isEqualTo(PDF_BYTES);

    // 커밋 후 비동기로 FastAPI 파싱을 호출하고 쪽수와 함께 READY 가 된다
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var material = materialRepository.findById(materialId).orElseThrow();
              assertThat(material.getStatus()).isEqualTo(MaterialStatus.READY);
              assertThat(material.getTotalPages()).isEqualTo(2);
            });
    Mockito.verify(fastApiClient)
        .parseMaterial(eq(materialId), eq(courseId), eq(stored.toString()));
  }

  @Test
  void keepsSeveralMaterialsInOneCourse() throws Exception {
    upload("1장 개요", accessToken);
    upload("2장 SQL", accessToken);

    mockMvc
        .perform(
            get("/api/v1/courses/{courseId}/materials", courseId)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        // 올린 순서대로 보여 준다
        .andExpect(jsonPath("$[0].title").value("1장 개요"))
        .andExpect(jsonPath("$[1].title").value("2장 SQL"));
  }

  @Test
  void marksMaterialFailedWhenParsingFails() throws Exception {
    willThrow(new IllegalStateException("FastAPI 응답 없음"))
        .given(fastApiClient)
        .parseMaterial(any(), any(), any());

    Long materialId = upload("실패 자료", accessToken);

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(materialRepository.findById(materialId).orElseThrow().getStatus())
                    .isEqualTo(MaterialStatus.FAILED));

    // 실패한 자료만 다시 시도할 수 있다
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/courses/{courseId}/materials/{materialId}/retry", courseId, materialId)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isAccepted());
  }

  @Test
  void rejectsNonPdfAndUnauthenticatedUploads() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/courses/{courseId}/materials", courseId)
                .file(new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes()))
                .param("title", "잘못된 파일")
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            multipart("/api/v1/courses/{courseId}/materials", courseId)
                .file(pdfFile())
                .param("title", "비로그인"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void servesPdfOnlyToOwner() throws Exception {
    Long materialId = upload("소유자 확인", accessToken);

    mockMvc
        .perform(
            get("/files/pdf/{id}.pdf", materialId).header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF));

    // 다른 사용자의 토큰으로는 404
    User other = userRepository.save(new User("other@example.com", "hash", "다른사람"));
    String otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    mockMvc
        .perform(
            get("/files/pdf/{id}.pdf", materialId).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/v1/courses/{courseId}/materials", courseId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteRemovesRowAndFile() throws Exception {
    Long materialId = upload("지울 자료", accessToken);
    Path stored = storageDir.resolve("pdf").resolve(materialId + ".pdf");
    assertThat(Files.exists(stored)).isTrue();

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/courses/{courseId}/materials/{materialId}", courseId, materialId)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNoContent());

    assertThat(materialRepository.findById(materialId)).isEmpty();
    assertThat(Files.exists(stored)).isFalse();
  }
}
