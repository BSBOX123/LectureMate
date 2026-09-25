package com.lecturemate.repository;

import com.lecturemate.domain.entity.CourseRecording;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CourseRecordingRepository extends JpaRepository<CourseRecording, Long> {

  /** 과목 소유 확인까지 마친 뒤 쓰는 조회. 다른 과목의 녹음이면 비어 있다. */
  Optional<CourseRecording> findByIdAndCourseId(Long id, Long courseId);

  List<CourseRecording> findAllByCourseIdOrderByCreatedAtDesc(Long courseId);

  /** 과목을 거쳐 소유자를 확인한다. WebSocket 처럼 녹음 ID 만 아는 경로에서 쓴다. */
  @Query("select r from CourseRecording r where r.id = :id and r.course.user.id = :userId")
  Optional<CourseRecording> findOwnedById(Long id, Long userId);
}
