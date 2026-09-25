package com.lecturemate.api.controller;

import com.lecturemate.service.RecordingService;
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
 * FastAPI 전사 완료 통보 수신 (SPEC §2.2-4).
 *
 * <p>{@code X-Internal-Secret} 헤더 검증은 {@code InternalSecretFilter} 가 담당한다.
 */
@RestController
@RequestMapping("/internal/v1/recordings")
public class InternalWebhookController {

  private static final Logger log = LoggerFactory.getLogger(InternalWebhookController.class);

  private final RecordingService recordingService;

  public InternalWebhookController(RecordingService recordingService) {
    this.recordingService = recordingService;
  }

  /** SPEC §2.2-4 요청 본문. */
  public record TranscriptionCompleteRequest(
      Long recordingId, String status, Integer segmentCount, Integer durationMs) {}

  @PostMapping("/{recordingId}/transcription-complete")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void transcriptionComplete(
      @PathVariable Long recordingId, @RequestBody TranscriptionCompleteRequest request) {
    if ("READY".equals(request.status())) {
      recordingService.markReady(recordingId, request.durationMs());
    } else {
      recordingService.markFailed(recordingId);
    }
    log.info(
        "전사 완료 통보 recordingId={} status={} segments={} 길이={}ms",
        recordingId,
        request.status(),
        request.segmentCount(),
        request.durationMs());
  }
}
