package com.lecturemate.repository;

import com.lecturemate.domain.entity.SlideAnnotation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlideAnnotationRepository extends JpaRepository<SlideAnnotation, Long> {

  /** (lecture_id, page_number) 인덱스를 그대로 쓰는 조회 (SPEC §2.1-4). */
  Optional<SlideAnnotation> findByLectureIdAndPageNumber(Long lectureId, int pageNumber);

  /** 시험 힌트가 있는 슬라이드 번호 (SPEC §2.1-12). */
  @Query(
      """
      select a.pageNumber from SlideAnnotation a
      where a.lecture.id = :lectureId and a.examHints is not null and a.examHints <> ''
      """)
  List<Integer> findPagesWithExamHints(@Param("lectureId") Long lectureId);
}
