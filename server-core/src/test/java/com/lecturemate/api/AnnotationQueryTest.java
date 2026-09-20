package com.lecturemate.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 슬라이드 자동 필기 조회 테스트 (SPEC §2.1-4).
 *
 * <p>slide_annotations 는 FastAPI 가 적재하므로 네이티브 SQL 로 준비한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnnotationQueryTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private LectureRepository lectureRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Long lectureId;
  private String accessToken;
  private String otherToken;

  @BeforeEach
  void setUp() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
    User owner = userRepository.save(new User("annotation-owner@example.com", "hash", "주인"));
    User other = userRepository.save(new User("annotation-other@example.com", "hash", "타인"));
    accessToken = jwtTokenService.issueAccessToken(owner, Instant.now());
    otherToken = jwtTokenService.issueAccessToken(other, Instant.now());
    Lecture lecture = lectureRepository.save(new Lecture(owner, "필기 조회 강의"));
    lectureId = lecture.getId();

    Long slideId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO lecture_slides (lecture_id, page_number, slide_text, layout_data)
            VALUES (?, 5, '다익스트라', '[]') RETURNING id
            """,
            Long.class,
            lectureId);
    jdbcTemplate.update(
        """
        INSERT INTO slide_annotations (slide_id, lecture_id, page_number, professor_summary,
          exam_hints, highlight_bboxes, confidence_score)
        VALUES (?, ?, 5, '음수 가중치 위험성 강조', '벨만-포드를 쓸 것',
          '[{"word": "음수 가중치", "bbox": [145.2, 310.5, 230.1, 328.0], "color": "#FFEB3B"}]'::jsonb,
          0.92)
        """,
        slideId,
        lectureId);
  }

  @AfterEach
  void tearDown() {
    lectureRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void returnsAnnotationForOwner() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/lectures/{id}/pages/{page}/annotations", lectureId, 5)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pageNumber").value(5))
        .andExpect(jsonPath("$.professorSummary").value("음수 가중치 위험성 강조"))
        .andExpect(jsonPath("$.examHints").value("벨만-포드를 쓸 것"))
        .andExpect(jsonPath("$.confidenceScore").value(0.92))
        .andExpect(jsonPath("$.highlights[0].word").value("음수 가중치"))
        .andExpect(jsonPath("$.highlights[0].color").value("#FFEB3B"))
        .andExpect(jsonPath("$.highlights[0].bbox[0]").value(145.2));
  }

  @Test
  void returnsNotFoundForPageWithoutAnnotation() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/lectures/{id}/pages/{page}/annotations", lectureId, 99)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void hidesAnnotationsOfOtherUsers() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/lectures/{id}/pages/{page}/annotations", lectureId, 5)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/lectures/{id}/pages/{page}/annotations", lectureId, 5))
        .andExpect(status().isUnauthorized());
  }
}
