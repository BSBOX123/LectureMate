package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.Lecture;
import com.lecturemate.domain.entity.LectureStatus;

/** SPEC §2.1-1 강의 생성 응답 및 강의 메타데이터 조회 응답. */
public record LectureResponse(
    Long lectureId, String title, LectureStatus status, String pdfUrl, String audioUrl) {

  public static LectureResponse from(Lecture lecture) {
    return new LectureResponse(
        lecture.getId(),
        lecture.getTitle(),
        lecture.getStatus(),
        lecture.getPdfUrl(),
        lecture.getAudioUrl());
  }
}
