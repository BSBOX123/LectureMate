package com.lecturemate.repository;

import com.lecturemate.domain.entity.Lecture;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

  /** 소유자 검사를 포함한 조회 (SPEC §2.1 인증 규칙: 소유자가 아니면 404). */
  Optional<Lecture> findByIdAndUserId(Long id, Long userId);

  List<Lecture> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
