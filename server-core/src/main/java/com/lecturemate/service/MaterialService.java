package com.lecturemate.service;

import com.lecturemate.api.dto.MaterialResponse;
import com.lecturemate.domain.entity.Course;
import com.lecturemate.domain.entity.CourseMaterial;
import com.lecturemate.domain.entity.MaterialStatus;
import com.lecturemate.repository.CourseMaterialRepository;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 과목에 PDF 자료를 올리고 관리한다 (SPEC §2.1-4 ~ §2.1-6). */
@Service
public class MaterialService {

  private final CourseService courseService;
  private final CourseMaterialRepository materialRepository;
  private final StorageService storageService;
  private final ApplicationEventPublisher eventPublisher;

  public MaterialService(
      CourseService courseService,
      CourseMaterialRepository materialRepository,
      StorageService storageService,
      ApplicationEventPublisher eventPublisher) {
    this.courseService = courseService;
    this.materialRepository = materialRepository;
    this.storageService = storageService;
    this.eventPublisher = eventPublisher;
  }

  /** 파싱을 요청해야 할 자료. 트랜잭션 커밋 후에 처리한다. */
  public record MaterialUploadedEvent(Long materialId, Long courseId, String pdfPath) {}

  /**
   * PDF 를 올린다 (SPEC §2.1-4).
   *
   * <p>FastAPI 가 {@code material_pages} 를 넣으려면 {@code course_materials} 행이 먼저 커밋되어 있어야
   * 한다. 그래서 파싱은 이벤트로 넘겨 커밋 후에 호출한다.
   */
  @Transactional
  public MaterialResponse upload(Long userId, Long courseId, String title, MultipartFile file) {
    Course course = courseService.requireOwned(userId, courseId);

    CourseMaterial material = materialRepository.save(new CourseMaterial(course, title));
    Path stored = storageService.storePdf(material.getId(), file);
    material.attachPdf("/files/pdf/" + material.getId() + ".pdf");

    eventPublisher.publishEvent(
        new MaterialUploadedEvent(material.getId(), courseId, stored.toString()));
    return MaterialResponse.from(material);
  }

  @Transactional(readOnly = true)
  public List<MaterialResponse> findAll(Long userId, Long courseId) {
    courseService.requireOwned(userId, courseId);
    return materialRepository.findAllByCourseIdOrderByCreatedAtAsc(courseId).stream()
        .map(MaterialResponse::from)
        .toList();
  }

  /** PDF 내려받기용 소유 확인 (SPEC §2.1-6). 자료 ID 만으로 과목을 거쳐 확인한다. */
  @Transactional(readOnly = true)
  public MaterialResponse findOwnedById(Long userId, Long materialId) {
    return materialRepository
        .findOwnedById(materialId, userId)
        .map(MaterialResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /** 자료와 PDF 파일을 지운다 (SPEC §2.1-5). 페이지 행은 DB CASCADE 가 지운다. */
  @Transactional
  public void delete(Long userId, Long courseId, Long materialId) {
    CourseMaterial material = requireOwned(userId, courseId, materialId);
    storageService.deleteMaterialFiles(materialId);
    materialRepository.delete(material);
  }

  /** 파싱 실패한 자료를 다시 파싱한다 (SPEC §2.1-6). */
  @Transactional
  public MaterialResponse retry(Long userId, Long courseId, Long materialId) {
    CourseMaterial material = requireOwned(userId, courseId, materialId);
    if (material.getStatus() != MaterialStatus.FAILED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "실패한 자료만 다시 시도할 수 있습니다.");
    }
    material.markProcessing();
    eventPublisher.publishEvent(
        new MaterialUploadedEvent(
            materialId, courseId, storageService.pdfPath(materialId).toString()));
    return MaterialResponse.from(material);
  }

  /** 파싱 완료 통보 (FastAPI 호출이 끝난 뒤 트리거가 부른다). */
  @Transactional
  public void markReady(Long materialId, Integer totalPages) {
    materialRepository.findById(materialId).ifPresent(material -> material.markReady(totalPages));
  }

  @Transactional
  public void markFailed(Long materialId) {
    materialRepository.findById(materialId).ifPresent(CourseMaterial::markFailed);
  }

  private CourseMaterial requireOwned(Long userId, Long courseId, Long materialId) {
    courseService.requireOwned(userId, courseId);
    return materialRepository
        .findByIdAndCourseId(materialId, courseId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
