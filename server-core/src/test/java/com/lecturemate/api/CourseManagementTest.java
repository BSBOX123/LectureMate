package com.lecturemate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.CourseMaterial;
import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.CourseMaterialRepository;
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

/** 과목(폴더) 생성·조회·수정·삭제 (SPEC §2.1-1 ~ §2.1-3). */
@SpringBootTest
@AutoConfigureMockMvc
class CourseManagementTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private CourseRepository courseRepository;
  @Autowired private CourseMaterialRepository materialRepository;
  @Autowired private CourseRecordingRepository recordingRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @MockitoBean private FastApiClient fastApiClient;

  private String ownerToken;
  private String otherToken;
  private User owner;

  @BeforeEach
  void setUp() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
    owner = userRepository.save(new User("course-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("course-other@example.com", "hash", "타인"));
    ownerToken = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
  }

  @AfterEach
  void tearDown() {
    courseRepository.deleteAll();
    userRepository.deleteAll();
  }

  private Long createCourse(String title) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/courses")
                    .header("Authorization", "Bearer " + ownerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + title + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value(title))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.valueOf(body.replaceAll(".*\"courseId\":(\\d+).*", "$1"));
  }

  @Test
  void createsAndListsOnlyOwnCourses() throws Exception {
    createCourse("데이터베이스");
    createCourse("운영체제");

    mockMvc
        .perform(get("/api/v1/courses").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        // 최신순
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].title").value("운영체제"));

    mockMvc
        .perform(get("/api/v1/courses").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void rejectsBlankTitleAndUnauthenticated() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/courses")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"비로그인\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void hidesOtherUsersCourse() throws Exception {
    Long courseId = createCourse("데이터베이스");

    mockMvc
        .perform(
            get("/api/v1/courses/{id}", courseId).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/v1/courses/{id}", courseId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void renamesCourse() throws Exception {
    Long courseId = createCourse("데이터베이스");

    mockMvc
        .perform(
            patch("/api/v1/courses/{id}", courseId)
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"데이터베이스 시스템\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("데이터베이스 시스템"));
  }

  @Test
  void deletingCourseRemovesMaterialsAndRecordings() throws Exception {
    Course course = courseRepository.save(new Course(owner, "지울 과목"));
    Long materialId = materialRepository.save(new CourseMaterial(course, "자료")).getId();
    Long recordingId = recordingRepository.save(new CourseRecording(course, "녹음")).getId();

    mockMvc
        .perform(
            delete("/api/v1/courses/{id}", course.getId())
                .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isNoContent());

    assertThat(courseRepository.findById(course.getId())).isEmpty();
    assertThat(materialRepository.findById(materialId)).isEmpty();
    assertThat(recordingRepository.findById(recordingId)).isEmpty();
  }
}
