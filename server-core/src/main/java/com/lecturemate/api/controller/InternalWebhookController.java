package com.lecturemate.api.controller;

import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.service.LectureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * FastAPI 배치 분석 완료 통보 수신 (SPEC §2.2-4).
 *
 * <p>{@code X-Internal-Secret} 헤더 검증은 {@code InternalSecretFilter} 가 담당한다.
 */
@RestController
@RequestMapping("/internal/v1/lectures")
public class InternalWebhookController {

  private static final Logger log = LoggerFactory.getLogger(InternalWebhookController.class);

  private final LectureService lectureService;

  public InternalWebhookController(LectureService lectureService) {
    this.lectureService = lectureService;
  }

  /** SPEC §2.2-4 요청 본문. */
  public record AnalysisCompleteRequest(
      Long lectureId, String status, Integer totalPagesAnalyzed, Integer matchedTranscriptSegments) {}

  @PostMapping("/{lectureId}/analysis-complete")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void analysisComplete(
      @PathVariable Long lectureId, @RequestBody AnalysisCompleteRequest request) {
    LectureStatus status =
        "READY".equals(request.status()) ? LectureStatus.READY : LectureStatus.FAILED;
    lectureService.changeStatus(lectureId, status);
    log.info(
        "분석 완료 통보 lectureId={} status={} pages={} segments={}",
        lectureId,
        status,
        request.totalPagesAnalyzed(),
        request.matchedTranscriptSegments());
  }
}
