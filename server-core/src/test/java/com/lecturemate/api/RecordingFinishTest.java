package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.client.FastApiClient.AnalyzeBatchResponse;
import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.InternalSecretFilter;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 녹음 종료 및 배치 분석 트리거, 완료 Webhook 테스트 (SPEC §2.1-3, §2.2-2, §2.2-4). */
@SpringBootTest
@AutoConfigureMockMvc
class RecordingFinishTest {

  @TempDir static Path storageDir;

  @DynamicPropertySource
  static void storagePath(DynamicPropertyRegistry registry) {
    registry.add("lecturemate.storage.local-path", () -> storageDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private LectureRepository lectureRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private Long lectureId;
  private String accessToken;

  @BeforeEach
  void setUp() throws Exception {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
    User user = userRepository.save(new User("finish-test@example.com", "hash", "녹음자"));
    accessToken = jwtTokenService.issueAccessToken(user, Instant.now());
    Lecture lecture = lectureRepository.save(new Lecture(user, "분석 대상 강의"));
    lectureId = lecture.getId();
    given(fastApiClient.analyzeBatch(any(), any()))
        .willReturn(new AnalyzeBatchResponse("batch_" + lectureId + "_20260920", "QUEUED"));
  }

  @AfterEach
  void tearDown() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
  }

  /** 녹음이 끝난 상태(WAV 존재 + audio_url)를 만든다. */
  private Path prepareRecording() throws Exception {
    Path wav = storageDir.resolve("audio").resolve(lectureId + ".wav");
    Files.createDirectories(wav.getParent());
    Files.writeString(wav, "RIFF fake wav");
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
    lecture.attachAudio("/files/audio/" + lectureId + ".wav");
    lectureRepository.save(lecture);
    return wav;
  }

  @Test
  void finishStartsAnalysisAndCallsFastApi() throws Exception {
    Path wav = prepareRecording();

    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/recording/finish", lectureId)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ANALYZING"))
        .andExpect(jsonPath("$.lectureId").value(lectureId))
        .andExpect(jsonPath("$.message").exists());

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                org.mockito.Mockito.verify(fastApiClient)
                    .analyzeBatch(eq(lectureId), eq(wav.toAbsolutePath().toString())));
    assertThat(lectureRepository.findById(lectureId).orElseThrow().getStatus())
        .isEqualTo(LectureStatus.ANALYZING);
  }

  @Test
  void finishWithoutRecordingReturnsConflict() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/recording/finish", lectureId)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isConflict());
  }

  @Test
  void finishRequiresOwnership() throws Exception {
    prepareRecording();
    User other = userRepository.save(new User("finish-other@example.com", "hash", "타인"));
    String otherToken = jwtTokenService.issueAccessToken(other, Instant.now());

    mockMvc
        .perform(
            post("/api/v1/lectures/{id}/recording/finish", lectureId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/v1/lectures/{id}/recording/finish", lectureId))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void webhookMarksLectureReady() throws Exception {
    lectureRepository.findById(lectureId).orElseThrow().changeStatus(LectureStatus.ANALYZING);

    mockMvc
        .perform(
            post("/internal/v1/lectures/{id}/analysis-complete", lectureId)
                .header(InternalSecretFilter.HEADER, "local-dev-internal-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"lectureId":%d,"status":"READY","totalPagesAnalyzed":0,
                     "matchedTranscriptSegments":12}
                    """
                        .formatted(lectureId)))
        .andExpect(status().isNoContent());

    assertThat(lectureRepository.findById(lectureId).orElseThrow().getStatus())
        .isEqualTo(LectureStatus.READY);
  }

  @Test
  void webhookMarksLectureFailedAndRequiresSecret() throws Exception {
    String body =
        """
        {"lectureId":%d,"status":"FAILED","totalPagesAnalyzed":0,"matchedTranscriptSegments":0}
        """
            .formatted(lectureId);

    mockMvc
        .perform(
            post("/internal/v1/lectures/{id}/analysis-complete", lectureId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post("/internal/v1/lectures/{id}/analysis-complete", lectureId)
                .header(InternalSecretFilter.HEADER, "local-dev-internal-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    assertThat(lectureRepository.findById(lectureId).orElseThrow().getStatus())
        .isEqualTo(LectureStatus.FAILED);
  }
}
