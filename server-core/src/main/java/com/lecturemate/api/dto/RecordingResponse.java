package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.CourseRecording;
import com.lecturemate.domain.entity.RecordingStatus;

/** 녹음 응답 (SPEC §2.1-7, §2.1-8). */
public record RecordingResponse(
    Long recordingId, String title, RecordingStatus status, String audioUrl, Integer durationMs) {

  public static RecordingResponse from(CourseRecording recording) {
    return new RecordingResponse(
        recording.getId(),
        recording.getTitle(),
        recording.getStatus(),
        recording.getAudioUrl(),
        recording.getDurationMs());
  }
}
