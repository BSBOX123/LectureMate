package com.lecturemate.service;

import com.lecturemate.api.dto.CourseResponse;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.CourseMaterial;
import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.CourseMaterialRepository;
import com.lecturemate.repository.CourseRecordingRepository;
import com.lecturemate.repository.CourseRepository;
import com.lecturemate.repository.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 과목(폴더) 관리 (SPEC §2.1-1 ~ §2.1-3).
 *
 * <p>소유자 확인({@link #requireOwned})을 여기서 한 곳으로 모은다. 자료·녹음 서비스는 반드시 이 메서드를 거쳐
 * 과목을 얻은 뒤 작업한다. 다른 사용자의 자료에 접근하는 경로가 생기지 않게 하기 위해서다.
 */
@Service
public class CourseService {

  private final CourseRepository courseRepository;
  private final CourseMaterialRepository materialRepository;
  private final CourseRecordingRepository recordingRepository;
  private final UserRepository userRepository;
  private final StorageService storageService;

  public CourseService(
      CourseRepository courseRepository,
      CourseMaterialRepository materialRepository,
      CourseRecordingRepository recordingRepository,
      UserRepository userRepository,
      StorageService storageService) {
    this.courseRepository = courseRepository;
    this.materialRepository = materialRepository;
    this.recordingRepository = recordingRepository;
    this.userRepository = userRepository;
    this.storageService = storageService;
  }

  @Transactional
  public CourseResponse create(Long userId, String title) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    return CourseResponse.from(courseRepository.save(new Course(user, title)));
  }

  @Transactional(readOnly = true)
  public List<CourseResponse> findAllOwned(Long userId) {
    return courseRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(CourseResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public CourseResponse findOwned(Long userId, Long courseId) {
    return CourseResponse.from(requireOwned(userId, courseId));
  }

  /** 소유한 과목을 돌려준다. 없거나 남의 것이면 404 (SPEC §2.1 인증 규칙). */
  @Transactional(readOnly = true)
  public Course requireOwned(Long userId, Long courseId) {
    return courseRepository
        .findByIdAndUserId(courseId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @Transactional
  public CourseResponse rename(Long userId, Long courseId, String title) {
    Course course = requireOwned(userId, courseId);
    course.rename(title);
    return CourseResponse.from(course);
  }

  /**
   * 과목과 그 안의 모든 자료·녹음을 지운다 (SPEC §2.1-3).
   *
   * <p>DB 행은 {@code ON DELETE CASCADE} 가 지우지만 디스크의 PDF·WAV 는 지워 주지 않는다. 그래서 파일을
   * 직접 지운다.
   *
   * <p>자료·녹음도 JPA 로 함께 지운다. 파일 경로를 알아내려고 이미 읽어 온 엔티티가 영속성 컨텍스트에 남아
   * 과목을 참조하고 있어서, 과목만 지우면 flush 할 때 참조 무결성 오류가 난다.
   */
  @Transactional
  public void delete(Long userId, Long courseId) {
    Course course = requireOwned(userId, courseId);

    List<CourseMaterial> materials = materialRepository.findAllByCourseIdOrderByCreatedAtAsc(courseId);
    for (CourseMaterial material : materials) {
      storageService.deleteMaterialFiles(material.getId());
    }
    materialRepository.deleteAll(materials);

    List<CourseRecording> recordings =
        recordingRepository.findAllByCourseIdOrderByCreatedAtDesc(courseId);
    for (CourseRecording recording : recordings) {
      storageService.deleteRecordingFiles(recording.getId());
    }
    recordingRepository.deleteAll(recordings);

    courseRepository.delete(course);
  }
}
