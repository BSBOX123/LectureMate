"use client";

import type { PageAnnotationResponse } from "@/types/api";

interface AnnotationOverlayProps {
  /** §2.1-4 응답. 분석 전이거나 조회 전이면 null */
  annotation: PageAnnotationResponse | null;
  /** PDF 좌표 → 화면 픽셀 배율 (PdfViewer 렌더링 viewport 기준) */
  scale: number;
}

/**
 * PDF 페이지 위 하이라이트 및 주석 오버레이 레이어.
 *
 * TODO: highlights 의 bbox 를 scale 로 변환해 canvas 에 그리기, professorSummary / examHints 표시.
 */
export default function AnnotationOverlay({
  annotation,
  scale,
}: AnnotationOverlayProps) {
  if (!annotation) {
    return null;
  }

  return (
    <div
      className="pointer-events-none absolute inset-0"
      data-scale={scale}
      aria-label={`${annotation.pageNumber} 페이지 주석 ${annotation.highlights.length}개`}
    />
  );
}
