package com.lecturemate.service;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.domain.entity.LectureStatus;
import com.lecturemate.service.LectureService.PdfUploadedEvent;
import com.lecturemate.service.LectureService.RecordingFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 업로드된 PDF 의 파싱을 FastAPI 에 요청한다 (SPEC §2.2-1).
 *
 * <p>업로드 응답은 곧바로 {@code PROCESSING} 으로 돌려주고(SPEC §2.1-1), 파싱은 커밋 이후 별도 스레드에서
 * 진행한 뒤 상태를 {@code READY} 또는 {@code FAILED} 로 바꾼다.
 */
@Component
public class PdfParseTrigger {


  private static final Logger log = LoggerFactory.getLogger(PdfParseTrigger.class);

  private final FastApiClient fastApiClient;
  private final LectureService lectureService;

  public PdfParseTrigger(FastApiClient fastApiClient, LectureService lectureService) {
    this.fastApiClient = fastApiClient;
    this.lectureService = lectureService;
  }

  /** 녹음 종료 후 FastAPI 배치 분석을 요청한다 (SPEC §2.2-2). 완료 통보는 Webhook 으로 받는다. */
  @Async
  @TransactionalEventListener
  public void onRecordingFinished(RecordingFinishedEvent event) {
    try {
      FastApiClient.AnalyzeBatchResponse response =
          fastApiClient.analyzeBatch(event.lectureId(), event.audioPath());
      log.info(
          "배치 분석 요청 완료 lectureId={} taskId={} status={}",
          event.lectureId(),
          response.task_id(),
          response.status());
    } catch (RuntimeException e) {
      log.error("배치 분석 요청 실패 lectureId={}", event.lectureId(), e);
      lectureService.changeStatus(event.lectureId(), LectureStatus.FAILED);
    }
  }

  @Async
  @TransactionalEventListener
  public void onPdfUploaded(PdfUploadedEvent event) {
    try {
      FastApiClient.PdfParseResponse response =
          fastApiClient.parsePdf(event.lectureId(), event.pdfPath());
      log.info(
          "PDF 파싱 완료 lectureId={} totalPages={}", event.lectureId(), response.total_pages());
      lectureService.changeStatus(event.lectureId(), LectureStatus.READY);
    } catch (RuntimeException e) {
      log.error("PDF 파싱 실패 lectureId={}", event.lectureId(), e);
      lectureService.changeStatus(event.lectureId(), LectureStatus.FAILED);
    }
  }
}
