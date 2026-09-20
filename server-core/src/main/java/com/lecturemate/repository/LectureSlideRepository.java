package com.lecturemate.repository;

import com.lecturemate.domain.entity.LectureSlide;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LectureSlideRepository extends JpaRepository<LectureSlide, Long> {

  boolean existsByLectureId(Long lectureId);
}
