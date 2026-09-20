package com.lecturemate.service;

import com.lecturemate.api.dto.LectureResponse;
import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.LectureRepository;
import com.lecturemate.repository.UserRepository;
import java.nio.file.Path;
import java.util.List;
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
  private final UserRepository userRepository;
  private final StorageService storageService;
  private final ApplicationEventPublisher eventPublisher;

  public LectureService(
      LectureRepository lectureRepository,
      UserRepository userRepository,
      StorageService storageService,
      ApplicationEventPublisher eventPublisher) {
    this.lectureRepository = lectureRepository;
    this.userRepository = userRepository;
    this.storageService = storageService;
    this.eventPublisher = eventPublisher;
  }

  /** PDF 파싱을 요청해야 할 강의. 트랜잭션 커밋 후에 처리한다. */
  public record PdfUploadedEvent(Long lectureId, String pdfPath) {}

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

  @Transactional(readOnly = true)
  public List<LectureResponse> findAllOwned(Long userId) {
    return lectureRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(LectureResponse::from)
        .toList();
  }

  /** 녹음이 끝나 WAV 가 만들어졌을 때 호출한다. 분석 트리거(§2.1-3)는 아직 별도 단계. */
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

  @Transactional
  public void changeStatus(Long lectureId, LectureStatus status) {
    lectureRepository.findById(lectureId).ifPresent(lecture -> lecture.changeStatus(status));
  }
}
