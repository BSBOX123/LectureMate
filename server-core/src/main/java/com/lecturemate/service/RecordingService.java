package com.lecturemate.service;

import com.lecturemate.api.dto.RecordingResponse;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.RecordingStatus;
import com.lecturemate.repository.CourseRecordingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 과목 안에서 여러 번 녹음하고 전사한다 (SPEC §2.1-7 ~ §2.1-11).
 *
 * <p>흐름: 녹음 생성(CREATED) → WebSocket 으로 PCM 수신(RECORDING) → 연결 종료 시 WAV 저장(UPLOADED)
 * → 전사 요청(ANALYZING) → Webhook 수신(READY / FAILED).
 *
 * <p>녹음이 끝나면 전사를 자동으로 시작한다. 예전에는 사용자가 버튼을 눌러야 했는데, 무엇을 눌러야 분석이
 * 되는지 알기 어렵다는 문제가 실제 사용에서 드러났다.
 */
@Service
public class RecordingService {

  private final CourseService courseService;
  private final CourseRecordingRepository recordingRepository;
  private final StorageService storageService;
  private final ApplicationEventPublisher eventPublisher;

  public RecordingService(
      CourseService courseService,
      CourseRecordingRepository recordingRepository,
      StorageService storageService,
      ApplicationEventPublisher eventPublisher) {
    this.courseService = courseService;
    this.recordingRepository = recordingRepository;
    this.storageService = storageService;
    this.eventPublisher = eventPublisher;
  }

  /** 전사를 요청해야 할 녹음. 트랜잭션 커밋 후에 처리한다. */
  public record RecordingFinishedEvent(Long recordingId, Long courseId, String audioPath) {}

  /** 녹음할 자리를 먼저 만든다. 클라이언트는 받은 ID 로 WebSocket 을 연다 (SPEC §2.1-7). */
  @Transactional
  public RecordingResponse create(Long userId, Long courseId, String title) {
    Course course = courseService.requireOwned(userId, courseId);
    return RecordingResponse.from(recordingRepository.save(new CourseRecording(course, title)));
  }

  @Transactional(readOnly = true)
  public List<RecordingResponse> findAll(Long userId, Long courseId) {
    courseService.requireOwned(userId, courseId);
    return recordingRepository.findAllByCourseIdOrderByCreatedAtDesc(courseId).stream()
        .map(RecordingResponse::from)
        .toList();
  }

  /** WebSocket 과 오디오 내려받기용 소유 확인. 녹음 ID 만으로 과목을 거쳐 확인한다. */
  @Transactional(readOnly = true)
  public CourseRecording requireOwnedById(Long userId, Long recordingId) {
    return recordingRepository
        .findOwnedById(recordingId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /** WebSocket 연결 시작 (SPEC §2.1-8). */
  @Transactional
  public void markRecording(Long recordingId) {
    recordingRepository
        .findById(recordingId)
        .ifPresent(recording -> recording.changeStatus(RecordingStatus.RECORDING));
  }

  /**
   * 녹음 종료: WAV 를 기록하고 곧바로 전사를 요청한다 (SPEC §2.1-8).
   *
   * <p>WAV 는 호출 전에 이미 만들어져 있다. 파일이 준비되기 전에 FastAPI 가 읽는 문제는 생기지 않는다.
   */
  @Transactional
  public void finishRecording(Long recordingId, String audioUrl) {
    recordingRepository
        .findById(recordingId)
        .ifPresent(
            recording -> {
              recording.attachAudio(audioUrl);
              startTranscription(recording);
            });
  }

  /** 전사 실패 후 다시 시도한다 (SPEC §2.1-10). */
  @Transactional
  public RecordingResponse retry(Long userId, Long courseId, Long recordingId) {
    CourseRecording recording = requireOwned(userId, courseId, recordingId);
    if (recording.getStatus() != RecordingStatus.FAILED
        && recording.getStatus() != RecordingStatus.UPLOADED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "전사를 기다리거나 실패한 녹음만 다시 시도할 수 있습니다.");
    }
    startTranscription(recording);
    return RecordingResponse.from(recording);
  }

  /** 녹음과 오디오 파일을 지운다 (SPEC §2.1-9). 전사 행은 DB CASCADE 가 지운다. */
  @Transactional
  public void delete(Long userId, Long courseId, Long recordingId) {
    CourseRecording recording = requireOwned(userId, courseId, recordingId);
    storageService.deleteRecordingFiles(recordingId);
    recordingRepository.delete(recording);
  }

  /** 전사 완료 통보 (SPEC §2.2-4 Webhook). */
  @Transactional
  public void markReady(Long recordingId, Integer durationMs) {
    recordingRepository.findById(recordingId).ifPresent(r -> r.markReady(durationMs));
  }

  @Transactional
  public void markFailed(Long recordingId) {
    recordingRepository
        .findById(recordingId)
        .ifPresent(r -> r.changeStatus(RecordingStatus.FAILED));
  }

  private void startTranscription(CourseRecording recording) {
    Path audioPath = storageService.audioPath(recording.getId());
    if (!Files.isReadable(audioPath)) {
      recording.changeStatus(RecordingStatus.FAILED);
      return;
    }
    recording.changeStatus(RecordingStatus.ANALYZING);
    eventPublisher.publishEvent(
        new RecordingFinishedEvent(
            recording.getId(), recording.getCourse().getId(), audioPath.toString()));
  }

  private CourseRecording requireOwned(Long userId, Long courseId, Long recordingId) {
    courseService.requireOwned(userId, courseId);
    return recordingRepository
        .findByIdAndCourseId(recordingId, courseId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
