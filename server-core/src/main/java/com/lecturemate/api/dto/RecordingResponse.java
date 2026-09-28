package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.RecordingStatus;

/** 녹음 응답 (SPEC §2.1-7, §2.1-8). */
public record RecordingResponse(
    Long recordingId,
    String title,
    RecordingStatus status,
    String audioUrl,
    Integer durationMs,
    /** 요약이 만들어져 있는지. 본문은 목록을 무겁게 만들지 않으려고 싣지 않는다 (§2.1-12 로 조회) */
    boolean hasSummary) {

  public static RecordingResponse from(CourseRecording recording) {
    return new RecordingResponse(
        recording.getId(),
        recording.getTitle(),
        recording.getStatus(),
        recording.getAudioUrl(),
        recording.getDurationMs(),
        recording.getSummary() != null);
  }
}
