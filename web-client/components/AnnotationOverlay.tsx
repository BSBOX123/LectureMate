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
      <div className="absolute right-2 top-2 max-w-xs rounded bg-white/95 p-3 text-xs shadow ring-1 ring-zinc-200">
        <p className="font-semibold text-zinc-900">교수님 요약</p>
        <p className="mt-1 text-zinc-700">{annotation.professorSummary}</p>
        {annotation.examHints && (
          <p className="mt-2 rounded bg-amber-50 p-2 text-amber-900">
            <span className="font-semibold">시험 힌트</span> {annotation.examHints}
          </p>
        )}
        <p className="mt-2 text-[10px] text-zinc-400">
          신뢰도 {Math.round(annotation.confidenceScore * 100)}%
        </p>
      </div>
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
