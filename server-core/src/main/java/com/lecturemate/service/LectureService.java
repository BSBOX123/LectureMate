package com.lecturemate.service;

import com.lecturemate.api.dto.LectureResponse;
import com.lecturemate.api.dto.PageAnnotationResponse;
import com.lecturemate.api.dto.SlideTimelineItem;
import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.LectureSlideRepository;
import com.lecturemate.repository.LectureTranscriptRepository;
import com.lecturemate.repository.SlideAnnotationRepository;
import com.lecturemate.repository.UserRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 강의 생성 및 조회 (SPEC §2.1-1). */
@Service
public class LectureService {

  private final LectureRepository lectureRepository;
  private final SlideAnnotationRepository slideAnnotationRepository;
  private final LectureTranscriptRepository lectureTranscriptRepository;
  private final LectureSlideRepository lectureSlideRepository;
  private final UserRepository userRepository;
  private final StorageService storageService;
  private final ApplicationEventPublisher eventPublisher;

  public LectureService(
      LectureRepository lectureRepository,
      SlideAnnotationRepository slideAnnotationRepository,
      LectureTranscriptRepository lectureTranscriptRepository,
      LectureSlideRepository lectureSlideRepository,
      UserRepository userRepository,
      StorageService storageService,
      ApplicationEventPublisher eventPublisher) {
    this.lectureRepository = lectureRepository;
    this.slideAnnotationRepository = slideAnnotationRepository;
    this.lectureTranscriptRepository = lectureTranscriptRepository;
    this.lectureSlideRepository = lectureSlideRepository;
    this.userRepository = userRepository;
    this.storageService = storageService;
    this.eventPublisher = eventPublisher;
  }

  /** PDF 파싱을 요청해야 할 강의. 트랜잭션 커밋 후에 처리한다. */
  public record PdfUploadedEvent(Long lectureId, String pdfPath) {}

  /** 녹음이 끝나 배치 분석을 요청해야 할 강의. 트랜잭션 커밋 후에 처리한다. */
  public record RecordingFinishedEvent(Long lectureId, String audioPath) {}

  @Transactional
  public LectureResponse create(Long userId, String title, MultipartFile file) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

    Lecture lecture = lectureRepository.save(new Lecture(user, title));
    Path stored = storageService.storePdf(lecture.getId(), file);
    lecture.attachPdf("/files/pdf/" + lecture.getId() + ".pdf");

    // FastAPI 가 lecture_slides 를 넣으려면 lectures 행이 커밋되어 있어야 한다.
    eventPublisher.publishEvent(new PdfUploadedEvent(lecture.getId(), stored.toString()));
    return LectureResponse.from(lecture);
  }

  @Transactional(readOnly = true)
  public LectureResponse findOwned(Long userId, Long lectureId) {
    return lectureRepository
        .findByIdAndUserId(lectureId, userId)
        .map(LectureResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /** 슬라이드별 자동 필기 조회 (SPEC §2.1-4). 분석 전이면 404. */
  @Transactional(readOnly = true)
  public PageAnnotationResponse findAnnotation(Long userId, Long lectureId, int pageNumber) {
    findOwned(userId, lectureId); // 소유자가 아니면 404
    return slideAnnotationRepository
        .findByLectureIdAndPageNumber(lectureId, pageNumber)
        .map(PageAnnotationResponse::from)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "해당 슬라이드의 자동 필기가 아직 없습니다."));
  }

  /** 슬라이드별 발화 분량과 시험 힌트 유무 (SPEC §2.1-12). */
  @Transactional(readOnly = true)
  public List<SlideTimelineItem> findTimeline(Long userId, Long lectureId) {
    findOwned(userId, lectureId); // 소유자가 아니면 404
    Set<Integer> pagesWithHints =
        Set.copyOf(slideAnnotationRepository.findPagesWithExamHints(lectureId));
    return lectureTranscriptRepository.findSpeechDurations(lectureId).stream()
        .map(
            row ->
                new SlideTimelineItem(
                    row.getPageNumber(),
                    row.getSpeechDurationMs(),
                    pagesWithHints.contains(row.getPageNumber())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<LectureResponse> findAllOwned(Long userId) {
    return lectureRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(LectureResponse::from)
        .toList();
  }

  /**
   * 녹음 종료 후 정밀 분석을 요청한다 (SPEC §2.1-3).
   *
   * @throws ResponseStatusException 소유자가 아니면 404, 녹음 파일이 없으면 409
   */
  @Transactional
  public LectureResponse finishRecording(Long userId, Long lectureId) {
    Lecture lecture =
        lectureRepository
            .findByIdAndUserId(lectureId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    Path audioPath = storageService.audioPath(lectureId);
    if (lecture.getAudioUrl() == null || !java.nio.file.Files.isReadable(audioPath)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "녹음된 오디오가 없습니다.");
    }

    lecture.changeStatus(LectureStatus.ANALYZING);
    eventPublisher.publishEvent(new RecordingFinishedEvent(lectureId, audioPath.toString()));
    return LectureResponse.from(lecture);
  }

  /** 녹음이 끝나 WAV 가 만들어졌을 때 호출한다 (WebSocket 종료 시점). */
  @Transactional
  public void attachAudio(Long lectureId, String audioUrl) {
    lectureRepository
        .findById(lectureId)
        .ifPresent(
            lecture -> {
              lecture.attachAudio(audioUrl);
              lecture.changeStatus(LectureStatus.READY);
            });
  }

  /** 강의와 파일을 삭제한다 (SPEC §2.1-13). 하위 데이터는 DB 의 ON DELETE CASCADE 가 지운다. */
  @Transactional
  public void delete(Long userId, Long lectureId) {
    Lecture lecture =
        lectureRepository
            .findByIdAndUserId(lectureId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    storageService.deleteLectureFiles(lectureId);
    lectureRepository.delete(lecture);
  }

  /**
   * 실패한 강의의 분석을 다시 시도한다 (SPEC §2.1-14).
   *
   * <p>슬라이드가 없으면 PDF 파싱부터, 있으면 오디오 정밀 분석을 다시 돌린다.
   */
  @Transactional
  public LectureResponse retry(Long userId, Long lectureId) {
    Lecture lecture =
        lectureRepository
            .findByIdAndUserId(lectureId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (lecture.getStatus() != LectureStatus.FAILED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "실패한 강의만 다시 시도할 수 있습니다.");
    }

    boolean hasSlides = lectureSlideRepository.existsByLectureId(lectureId);
    if (!hasSlides) {
      lecture.changeStatus(LectureStatus.PROCESSING);
      eventPublisher.publishEvent(
          new PdfUploadedEvent(lectureId, storageService.pdfPath(lectureId).toString()));
    } else {
      Path audioPath = storageService.audioPath(lectureId);
      if (lecture.getAudioUrl() == null || !java.nio.file.Files.isReadable(audioPath)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "다시 시도할 녹음이 없습니다.");
      }
      lecture.changeStatus(LectureStatus.ANALYZING);
      eventPublisher.publishEvent(new RecordingFinishedEvent(lectureId, audioPath.toString()));
    }
    return LectureResponse.from(lecture);
  }

  @Transactional
  public void changeStatus(Long lectureId, LectureStatus status) {
    lectureRepository.findById(lectureId).ifPresent(lecture -> lecture.changeStatus(status));
  }
}
