package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.RecordingStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.CourseRecordingRepository;
import com.lecturemate.repository.CourseRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 녹음 생성·목록·전사 완료 Webhook (SPEC §2.1-7 ~ §2.1-10, §2.2-4). */
@SpringBootTest
@AutoConfigureMockMvc
class RecordingFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private CourseRepository courseRepository;
  @Autowired private CourseRecordingRepository recordingRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  @org.springframework.beans.factory.annotation.Value("${lecturemate.internal.secret}")
  private String internalSecret;

  private String ownerToken;
  private String otherToken;
  private Long courseId;
  private Course course;

  @BeforeEach
  void setUp() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("rec-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("rec-other@example.com", "hash", "타인"));
    ownerToken = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    course = courseRepository.save(new Course(owner, "데이터베이스"));
    courseId = course.getId();
  }

  @AfterEach
  void tearDown() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
  }

  private Long createRecording(String title, String token) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/courses/{courseId}/recordings", courseId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + title + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.valueOf(body.replaceAll(".*\"recordingId\":(\\d+).*", "$1"));
  }

  @Test
  void createsRecordingReadyForWebSocket() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/courses/{courseId}/recordings", courseId)
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"10월 2일 수업\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("10월 2일 수업"))
        // 아직 소리가 들어오기 전이라 CREATED, 오디오도 없다
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.audioUrl").doesNotExist());
  }

  @Test
  void keepsSeveralRecordingsInOneCourse() throws Exception {
    createRecording("1주차", ownerToken);
    createRecording("2주차", ownerToken);

    mockMvc
        .perform(
            get("/api/v1/courses/{courseId}/recordings", courseId)
                .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        // 최신순
        .andExpect(jsonPath("$[0].title").value("2주차"));
  }

  @Test
  void rejectsOtherUsersCourse() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/courses/{courseId}/recordings", courseId)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"남의 과목\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void webhookMarksRecordingReadyWithDuration() throws Exception {
    CourseRecording recording =
        recordingRepository.save(new CourseRecording(course, "전사 대상"));
    recording.changeStatus(RecordingStatus.ANALYZING);
    recordingRepository.save(recording);

    mockMvc
        .perform(
            post("/internal/v1/recordings/{id}/transcription-complete", recording.getId())
                .header("X-Internal-Secret", internalSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"recordingId\":%d,\"status\":\"READY\",\"segmentCount\":436,\"durationMs\":2188460}"
                        .formatted(recording.getId())))
        .andExpect(status().isNoContent());

    CourseRecording updated = recordingRepository.findById(recording.getId()).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(RecordingStatus.READY);
    assertThat(updated.getDurationMs()).isEqualTo(2188460);
  }

  @Test
  void webhookMarksRecordingFailed() throws Exception {
    CourseRecording recording = recordingRepository.save(new CourseRecording(course, "실패 녹음"));

    mockMvc
        .perform(
            post("/internal/v1/recordings/{id}/transcription-complete", recording.getId())
                .header("X-Internal-Secret", internalSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"recordingId\":%d,\"status\":\"FAILED\",\"segmentCount\":0,\"durationMs\":0}"
                        .formatted(recording.getId())))
        .andExpect(status().isNoContent());

    assertThat(recordingRepository.findById(recording.getId()).orElseThrow().getStatus())
        .isEqualTo(RecordingStatus.FAILED);
  }

  @Test
  void webhookRequiresInternalSecret() throws Exception {
    CourseRecording recording = recordingRepository.save(new CourseRecording(course, "보호"));

    mockMvc
        .perform(
            post("/internal/v1/recordings/{id}/transcription-complete", recording.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"recordingId\":%d,\"status\":\"READY\",\"segmentCount\":1,\"durationMs\":1}"
                        .formatted(recording.getId())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void transcribeRejectsRecordingWithoutAudio() throws Exception {
    Long recordingId = createRecording("오디오 없음", ownerToken);

    // CREATED 상태(녹음 전)에는 전사할 오디오가 없다
    mockMvc
        .perform(
            post("/api/v1/courses/{courseId}/recordings/{id}/transcribe", courseId, recordingId)
                .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isConflict());
  }

  /** 녹음이 저장된 뒤 사용자가 눌러야 전사가 시작된다 (SPEC §2.1-10). */
  @Test
  void transcribeStartsFromUploadedState() throws Exception {
    CourseRecording recording = recordingRepository.save(new CourseRecording(course, "전사 대상"));
    recording.attachAudio("/files/audio/" + recording.getId() + ".wav");
    recordingRepository.save(recording);
    assertThat(recordingRepository.findById(recording.getId()).orElseThrow().getStatus())
        .isEqualTo(RecordingStatus.UPLOADED);

    // 오디오 파일이 없으면 FAILED 로 떨어진다 (실제 파일은 이 테스트에서 만들지 않는다)
    mockMvc
        .perform(
            post("/api/v1/courses/{courseId}/recordings/{id}/transcribe", courseId, recording.getId())
                .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isAccepted());
  }
}
