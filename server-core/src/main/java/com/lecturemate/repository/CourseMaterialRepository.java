package com.lecturemate.repository;

import com.lecturemate.domain.entity.CourseMaterial;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CourseMaterialRepository extends JpaRepository<CourseMaterial, Long> {

  /** 과목 소유 확인까지 마친 뒤 쓰는 조회. 다른 과목의 자료면 비어 있다. */
  Optional<CourseMaterial> findByIdAndCourseId(Long id, Long courseId);

  List<CourseMaterial> findAllByCourseIdOrderByCreatedAtAsc(Long courseId);

  /** 과목을 거쳐 소유자를 확인한다. PDF 내려받기처럼 자료 ID 만 아는 경로에서 쓴다. */
  @Query("select m from CourseMaterial m where m.id = :id and m.course.user.id = :userId")
  Optional<CourseMaterial> findOwnedById(Long id, Long userId);
}
