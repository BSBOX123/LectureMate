package com.lecturemate.domain.entity;

/** 녹음 처리 상태 ({@code course_recordings.status}). */
public enum RecordingStatus {
  /** 행만 만들어진 상태. 아직 WebSocket 으로 소리가 들어오지 않았다 */
  CREATED,
  /** 녹음 중 */
  RECORDING,
  /** 녹음 파일 저장 완료, 전사 대기 */
  UPLOADED,
  /** 전사 중 */
  ANALYZING,
  /** 전사 완료. 검색 가능 */
  READY,
  /** 전사 실패. 재시도할 수 있다 */
  FAILED
}
