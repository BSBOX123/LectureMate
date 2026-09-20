package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.LectureStatus;

/** SPEC §2.1-3 녹음 종료 및 분석 트리거 응답. */
public record RecordingFinishResponse(Long lectureId, LectureStatus status, String message) {}
