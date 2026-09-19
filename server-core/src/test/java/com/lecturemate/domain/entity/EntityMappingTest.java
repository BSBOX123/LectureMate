package com.lecturemate.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * 엔티티 매핑을 실제 db/init.sql 스키마에 대해 검증한다. 각 테스트는 트랜잭션 롤백되어 데이터가 남지 않는다.
 *
 * <p>로컬 postgres 컨테이너가 떠 있어야 한다 ({@code docker compose up -d postgres}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingTest {

  @Autowired private EntityManager em;

  @Test
  void persistsUserAndLectureWithDefaults() {
    User user = new User("student@example.com", "hash", "학생");
    Lecture lecture = new Lecture(user, "컴퓨터 알고리즘 5강");
    em.persist(user);
    em.persist(lecture);
    em.flush();
    em.clear();

    Lecture found = em.find(Lecture.class, lecture.getId());
    assertThat(found.getStatus()).isEqualTo(LectureStatus.INITIALIZED);
    assertThat(found.getUser().getEmail()).isEqualTo("student@example.com");
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNotNull();
  }

  @Test
  void readsFastApiOwnedRowsIncludingJsonb() {
    User user = new User("student@example.com", "hash", "학생");
    Lecture lecture = new Lecture(user, "컴퓨터 알고리즘 5강");
    em.persist(user);
    em.persist(lecture);
    em.flush();

    // FastAPI 가 적재하는 테이블은 네이티브 SQL 로 흉내낸다.
    Long slideId =
        (Long)
            em.createNativeQuery(
                    """
                    INSERT INTO lecture_slides (lecture_id, page_number, slide_text, layout_data)
                    VALUES (?1, 5, '다익스트라',
                            '[{"word": "Dijkstra", "bbox": [100.2, 150.4, 180.0, 168.2]}]')
                    RETURNING id
                    """,
                    Long.class)
                .setParameter(1, lecture.getId())
                .getSingleResult();
    em.createNativeQuery(
            """
            INSERT INTO lecture_transcripts
              (lecture_id, start_time_ms, end_time_ms, speaker_text, matched_slide_page)
            VALUES (?1, 15000, 18000, '오늘 다룰 내용은 다익스트라입니다.', 5)
            """)
        .setParameter(1, lecture.getId())
        .executeUpdate();
    Long annotationId =
        (Long)
            em.createNativeQuery(
                    """
                    INSERT INTO slide_annotations (slide_id, lecture_id, page_number,
                      professor_summary, exam_hints, highlight_bboxes, confidence_score)
                    VALUES (?1, ?2, 5, '음수 가중치 강조', '벨만-포드 사용',
                      '[{"word": "음수 가중치", "bbox": [145.2, 310.5, 230.1, 328.0],
                         "color": "#FFEB3B"}]', 0.92)
                    RETURNING id
                    """,
                    Long.class)
                .setParameter(1, slideId)
                .setParameter(2, lecture.getId())
                .getSingleResult();
    em.clear();

    LectureSlide slide = em.find(LectureSlide.class, slideId);
    assertThat(slide.getLayoutData())
        .containsExactly(new LayoutWord("Dijkstra", List.of(100.2, 150.4, 180.0, 168.2)));
    assertThat(slide.getCreatedAt()).isNotNull();

    LectureTranscript transcript =
        em.createQuery(
                "select t from LectureTranscript t where t.lecture.id = :id",
                LectureTranscript.class)
            .setParameter("id", lecture.getId())
            .getSingleResult();
    assertThat(transcript.getMatchedSlidePage()).isEqualTo(5);

    SlideAnnotation annotation = em.find(SlideAnnotation.class, annotationId);
    assertThat(annotation.getSlide().getId()).isEqualTo(slideId);
    assertThat(annotation.getConfidenceScore()).isEqualTo(0.92);
    assertThat(annotation.getHighlightBboxes())
        .containsExactly(
            new HighlightBox("음수 가중치", List.of(145.2, 310.5, 230.1, 328.0), "#FFEB3B"));
  }
}
