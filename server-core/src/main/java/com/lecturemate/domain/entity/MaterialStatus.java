package com.lecturemate.domain.entity;

/** PDF 자료 처리 상태 ({@code course_materials.status}). */
public enum MaterialStatus {
  /** 업로드 완료, FastAPI 가 페이지 추출·임베딩 중 */
  PROCESSING,
  /** 검색 가능 */
  READY,
  /** 파싱 실패. 재시도할 수 있다 */
  FAILED
}
