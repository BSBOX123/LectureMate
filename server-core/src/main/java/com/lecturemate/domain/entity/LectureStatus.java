package com.lecturemate.domain.entity;

/** 강의 처리 상태 ({@code lectures.status}). */
public enum LectureStatus {
  INITIALIZED,
  PROCESSING,
  RECORDING,
  ANALYZING,
  READY,
  FAILED
}
