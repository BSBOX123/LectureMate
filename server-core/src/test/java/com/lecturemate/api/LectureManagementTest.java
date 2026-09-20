package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 타임라인 조회, 강의 삭제, 분석 재시도 (SPEC §2.1-12 ~ §2.1-14). */
@SpringBootTest
@AutoConfigureMockMvc
class LectureManagementTest {

  @TempDir static Path storageDir;

  @DynamicPropertySource
  static void storagePath(DynamicPropertyRegistry registry) {
    registry.add("lecturemate.storage.local-path", () -> storageDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private LectureRepository lectureRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private FastApiClient fastApiClient;

  private Long lectureId;
  private String token;
  private String otherToken;

  @BeforeEach
  void setUp() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("manage-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("manage-other@example.com", "hash", "타인"));
    token = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    lectureId = lectureRepository.save(new Lecture(owner, "관리 테스트 강의")).getId();
  }

  @AfterEach
  void tearDown() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
  }

  /** FastAPI 가 적재하는 데이터를 네이티브 SQL 로 준비한다. */
  private Long insertSlide(int pageNumber) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO lecture_slides (lecture_id, page_number, slide_text, layout_data)
        VALUES (?, ?, '슬라이드', '[]') RETURNING id
        """,
        Long.class,
        lectureId,
        pageNumber);
  }

  private void insertTranscript(int page, int startMs, int endMs) {
    jdbcTemplate.update(
        """
        INSERT INTO lecture_transcripts
          (lecture_id, start_time_ms, end_time_ms, speaker_text, matched_slide_page)
        VALUES (?, ?, ?, '발화', ?)
        """,
        lectureId,
        startMs,
        endMs,
        page);
  }

  private void insertAnnotation(Long slideId, int page, String examHints) {
    jdbcTemplate.update(
        """
        INSERT INTO slide_annotations (slide_id, lecture_id, page_number, professor_summary,
          exam_hints, highlight_bboxes, confidence_score)
        VALUES (?, ?, ?, '요약', ?, '[]'::jsonb, 0.8)
        """,
        slideId,
        lectureId,
        page,
        examHints);
  }

  // --- §2.1-12 타임라인 -------------------------------------------------

  @Test
  void timelineSumsSpeechPerPageAndFlagsExamHints() throws Exception {
    Long slide1 = insertSlide(1);
    insertSlide(2);
    insertTranscript(1, 0, 3000);
    insertTranscript(1, 3000, 5000); // 1쪽 합계 5000ms
    insertTranscript(2, 5000, 9000); // 2쪽 4000ms
    insertAnnotation(slide1, 1, "중간고사 출제"); // 1쪽만 힌트 있음

    mockMvc
        .perform(
            get("/api/v1/lectures/{id}/timeline", lectureId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].pageNumber").value(1))
        .andExpect(jsonPath("$[0].speechDurationMs").value(5000))
        .andExpect(jsonPath("$[0].hasExamHint").value(true))
        .andExpect(jsonPath("$[1].pageNumber").value(2))
        .andExpect(jsonPath("$[1].speechDurationMs").value(4000))
        .andExpect(jsonPath("$[1].hasExamHint").value(false));
  }

  @Test
  void timelineExcludesUnmatchedSegmentsAndProtectsOtherUsers() throws Exception {
    jdbcTemplate.update(
        """
        INSERT INTO lecture_transcripts
          (lecture_id, start_time_ms, end_time_ms, speaker_text, matched_slide_page)
        VALUES (?, 0, 3000, '매칭 안 된 발화', NULL)
        """,
        lectureId);

    mockMvc
        .perform(
            get("/api/v1/lectures/{id}/timeline", lectureId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mockMvc
        .perform(
            get("/api/v1/lectures/{id}/timeline", lectureId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  // --- §2.1-13 삭제 -----------------------------------------------------

  @Test
  void deleteRemovesLectureChildRowsAndFiles() throws Exception {
    Long slideId = insertSlide(1);
    insertTranscript(1, 0, 3000);
    insertAnnotation(slideId, 1, null);

    Path pdf = storageDir.resolve("pdf").resolve(lectureId + ".pdf");
    Path wav = storageDir.resolve("audio").resolve(lectureId + ".wav");
    Files.createDirectories(pdf.getParent());
    Files.createDirectories(wav.getParent());
    Files.writeString(pdf, "%PDF-1.7");
    Files.writeString(wav, "RIFF");

    mockMvc
        .perform(
            delete("/api/v1/lectures/{id}", lectureId).header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    assertThat(lectureRepository.findById(lectureId)).isEmpty();
    assertThat(Files.exists(pdf)).isFalse();
    assertThat(Files.exists(wav)).isFalse();
    // ON DELETE CASCADE 로 하위 데이터도 사라진다
    assertThat(countRows("lecture_slides")).isZero();
    assertThat(countRows("lecture_transcripts")).isZero();
    assertThat(countRows("slide_annotations")).isZero();
  }

  private int countRows(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE lecture_id = ?", Integer.class, lectureId);
  }

  @Test
  void deleteRequiresOwnership() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/lectures/{id}", lectureId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/v1/lectures/{id}", lectureId)).andExpect(status().isUnauthorized());
    assertThat(lectureRepository.findById(lectureId)).isPresent();
  }

  // --- §2.1-14 재시도 ---------------------------------------------------

  @Test
  void retryRerunsPdfParsingWhenSlidesAreMissing() throws Exception {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
    lecture.changeStatus(LectureStatus.FAILED);
    lectureRepository.save(lecture);
    Mockito.when(fastApiClient.parsePdf(any(), any()))
        .thenReturn(new FastApiClient.PdfParseResponse(2, "COMPLETED"));

    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/retry", lectureId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PROCESSING"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> Mockito.verify(fastApiClient).parsePdf(any(), any()));
  }

  @Test
  void retryRerunsBatchAnalysisWhenSlidesExist() throws Exception {
    insertSlide(1);
    Path wav = storageDir.resolve("audio").resolve(lectureId + ".wav");
    Files.createDirectories(wav.getParent());
    Files.writeString(wav, "RIFF");
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
    lecture.attachAudio("/files/audio/" + lectureId + ".wav");
    lecture.changeStatus(LectureStatus.FAILED);
    lectureRepository.save(lecture);
    Mockito.when(fastApiClient.analyzeBatch(any(), any()))
        .thenReturn(new FastApiClient.AnalyzeBatchResponse("batch", "QUEUED"));

    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/retry", lectureId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ANALYZING"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> Mockito.verify(fastApiClient).analyzeBatch(any(), any()));
  }

  @Test
  void retryOnlyAllowedForFailedLectures() throws Exception {
    // 기본 상태는 INITIALIZED
    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/retry", lectureId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/retry", lectureId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }
}
