package com.lecturemate.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * 엔티티 매핑을 실제 Flyway 스키마에 대해 검증한다. 각 테스트는 트랜잭션 롤백되어 데이터가 남지 않는다.
 *
 * <p>로컬 postgres 컨테이너가 떠 있어야 한다 ({@code docker-compose up -d postgres}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingTest {

  @Autowired private EntityManager em;

  private Course persistCourse() {
    User user = new User("student@example.com", "hash", "학생");
    Course course = new Course(user, "데이터베이스");
    em.persist(user);
    em.persist(course);
    return course;
  }

  @Test
  void persistsCourseWithTimestamps() {
    Course course = persistCourse();
    em.flush();
    em.clear();

    Course found = em.find(Course.class, course.getId());
    assertThat(found.getTitle()).isEqualTo("데이터베이스");
    assertThat(found.getUser().getEmail()).isEqualTo("student@example.com");
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNotNull();
  }

  @Test
  void courseHoldsManyMaterialsAndRecordings() {
    Course course = persistCourse();
    CourseMaterial first = new CourseMaterial(course, "1장 관계형 모델");
    CourseMaterial second = new CourseMaterial(course, "2장 SQL");
    CourseRecording recording = new CourseRecording(course, "10월 2일 수업");
    em.persist(first);
    em.persist(second);
    em.persist(recording);
    em.flush();
    em.clear();

    assertThat(
            em.createQuery(
                    "select m from CourseMaterial m where m.course.id = :id", CourseMaterial.class)
                .setParameter("id", course.getId())
                .getResultList())
        .hasSize(2);

    // 상태 기본값: 자료는 파싱 대기, 녹음은 아직 소리가 들어오기 전
    assertThat(em.find(CourseMaterial.class, first.getId()).getStatus())
        .isEqualTo(MaterialStatus.PROCESSING);
    assertThat(em.find(CourseRecording.class, recording.getId()).getStatus())
        .isEqualTo(RecordingStatus.CREATED);
  }

  @Test
  void materialBecomesReadyWithPageCount() {
    Course course = persistCourse();
    CourseMaterial material = new CourseMaterial(course, "2장 SQL");
    em.persist(material);
    material.attachPdf("/files/pdf/" + 1 + ".pdf");
    material.markReady(128);
    em.flush();
    em.clear();

    CourseMaterial found = em.find(CourseMaterial.class, material.getId());
    assertThat(found.getStatus()).isEqualTo(MaterialStatus.READY);
    assertThat(found.getTotalPages()).isEqualTo(128);
    assertThat(found.getPdfUrl()).isEqualTo("/files/pdf/1.pdf");
  }

  @Test
  void deletingCourseCascadesToFastApiOwnedRows() {
    Course course = persistCourse();
    CourseMaterial material = new CourseMaterial(course, "2장 SQL");
    em.persist(material);
    em.flush();

    // FastAPI 가 적재하는 테이블은 네이티브 SQL 로 흉내낸다 (ORM 에 매핑하지 않는다)
    em.createNativeQuery(
            """
            INSERT INTO material_pages (material_id, course_id, page_number, page_text, layout_data)
            VALUES (?1, ?2, 5, '다익스트라',
                    '[{"word": "Dijkstra", "bbox": [100.2, 150.4, 180.0, 168.2]}]')
            """)
        .setParameter(1, material.getId())
        .setParameter(2, course.getId())
        .executeUpdate();

    // DB 의 ON DELETE CASCADE 자체를 확인한다. JPA 로 지우면 Hibernate 가 순서를 정리해 버려서
    // 제약이 실제로 걸려 있는지 알 수 없다. 영속성 컨텍스트를 비우고 네이티브 삭제를 쓴다.
    em.clear();
    em.createNativeQuery("DELETE FROM courses WHERE id = ?1")
        .setParameter(1, course.getId())
        .executeUpdate();

    Number remaining =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM material_pages WHERE course_id = ?1")
                .setParameter(1, course.getId())
                .getSingleResult();
    assertThat(remaining.intValue()).isZero();
  }
}
