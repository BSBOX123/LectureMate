package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.CourseRecording;
import java.time.OffsetDateTime;

/** 녹음 요약 응답 (SPEC §2.1-12). */
public record RecordingSummaryResponse(
    Long recordingId, String title, String summary, OffsetDateTime summarizedAt) {

  public static RecordingSummaryResponse from(CourseRecording recording) {
    return new RecordingSummaryResponse(
        recording.getId(),
        recording.getTitle(),
        recording.getSummary(),
        recording.getSummarizedAt());
  }
}
