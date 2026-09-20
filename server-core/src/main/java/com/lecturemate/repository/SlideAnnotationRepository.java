package com.lecturemate.repository;

import com.lecturemate.domain.entity.SlideAnnotation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlideAnnotationRepository extends JpaRepository<SlideAnnotation, Long> {

  /** (lecture_id, page_number) 인덱스를 그대로 쓰는 조회 (SPEC §2.1-4). */
  Optional<SlideAnnotation> findByLectureIdAndPageNumber(Long lectureId, int pageNumber);
}
