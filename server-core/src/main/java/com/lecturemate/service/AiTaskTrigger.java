package com.lecturemate.service;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.service.MaterialService.MaterialUploadedEvent;
import com.lecturemate.service.RecordingService.RecordingFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * FastAPI 에 PDF 파싱·녹음 전사를 요청한다 (SPEC §2.2-1, §2.2-2).
 *
 * <p>두 작업 모두 응답을 먼저 돌려준 뒤 커밋 이후 별도 스레드에서 진행한다. FastAPI 가 하위 행을 넣으려면
 * 부모 행이 커밋되어 있어야 하기 때문이다.
 *
 * <p>PDF 파싱은 결과를 바로 받아 상태를 바꾸고, 전사는 오래 걸려 Webhook(§2.2-4)으로 완료를 받는다.
 */
@Component
public class AiTaskTrigger {

  private static final Logger log = LoggerFactory.getLogger(AiTaskTrigger.class);

  private final FastApiClient fastApiClient;
  private final MaterialService materialService;
  private final RecordingService recordingService;

  public AiTaskTrigger(
      FastApiClient fastApiClient,
      MaterialService materialService,
      RecordingService recordingService) {
    this.fastApiClient = fastApiClient;
    this.materialService = materialService;
    this.recordingService = recordingService;
  }

  @Async
  @TransactionalEventListener
  public void onMaterialUploaded(MaterialUploadedEvent event) {
    try {
      FastApiClient.MaterialParseResponse response =
          fastApiClient.parseMaterial(event.materialId(), event.courseId(), event.pdfPath());
      log.info(
          "PDF 파싱 완료 materialId={} totalPages={}", event.materialId(), response.total_pages());
      materialService.markReady(event.materialId(), response.total_pages());
    } catch (RuntimeException e) {
      log.error("PDF 파싱 실패 materialId={}", event.materialId(), e);
      materialService.markFailed(event.materialId());
    }
  }

  @Async
  @TransactionalEventListener
  public void onRecordingFinished(RecordingFinishedEvent event) {
    try {
      FastApiClient.TranscribeResponse response =
          fastApiClient.transcribe(event.recordingId(), event.courseId(), event.audioPath());
      log.info(
          "전사 요청 완료 recordingId={} taskId={} status={}",
          event.recordingId(),
          response.task_id(),
          response.status());
    } catch (RuntimeException e) {
      log.error("전사 요청 실패 recordingId={}", event.recordingId(), e);
      recordingService.markFailed(event.recordingId());
    }
  }
}
