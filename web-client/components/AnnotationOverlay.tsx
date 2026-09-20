"use client";

import { toOverlayRect } from "@/lib/pdf";
import type { PageAnnotationResponse } from "@/types/api";

interface AnnotationOverlayProps {
  /** §2.1-4 응답. 분석 전이거나 조회 전이면 null */
  annotation: PageAnnotationResponse | null;
  /** PdfViewer 가 페이지를 그릴 때 쓴 배율 (PDF 포인트 → CSS 픽셀) */
  scale: number;
}

/** PDF 페이지 위 하이라이트 오버레이 (SPEC §4.3, §2.1-4). */
export default function AnnotationOverlay({ annotation, scale }: AnnotationOverlayProps) {
  if (!annotation) {
    return null;
  }

  return (
    <div className="pointer-events-none absolute inset-0">
      {annotation.highlights.map((highlight, index) => {
        const rect = toOverlayRect(highlight.bbox, scale);
        return (
          <mark
            key={`${highlight.word}-${index}`}
            title={highlight.word}
            className="absolute rounded-sm opacity-40 mix-blend-multiply"
            style={{
              left: rect.left,
              top: rect.top,
              width: rect.width,
              height: rect.height,
              backgroundColor: highlight.color,
            }}
          />
        );
      })}
    </div>
  );
}
