"use client";

import type { ReactNode } from "react";

interface PdfViewerProps {
  /** §2.1-1 응답의 pdfUrl. 업로드 전이면 null */
  pdfUrl: string | null;
  pageNumber: number;
  onPageChange: (pageNumber: number) => void;
  /** 페이지 위에 겹쳐 그릴 레이어 (AnnotationOverlay) */
  children?: ReactNode;
}

/**
 * PDF.js 기반 슬라이드 렌더러.
 *
 * TODO: pdfjs-dist 로 pdfUrl 로드, pageNumber 페이지를 canvas 에 렌더링, 이전/다음 페이지 이동.
 */
export default function PdfViewer({
  pdfUrl,
  pageNumber,
  onPageChange,
  children,
}: PdfViewerProps) {
  return (
    <section className="flex h-full flex-col">
      <div className="relative flex flex-1 items-center justify-center rounded border border-zinc-200 bg-zinc-50">
        <p className="text-sm text-zinc-500">
          {pdfUrl ? `PDF ${pageNumber} 페이지` : "업로드된 PDF가 없습니다"}
        </p>
        {children}
      </div>
      <div className="flex items-center justify-center gap-4 py-2 text-sm">
        <button
          type="button"
          className="rounded border px-3 py-1 disabled:opacity-40"
          disabled={pageNumber <= 1}
          onClick={() => onPageChange(pageNumber - 1)}
        >
          이전
        </button>
        <span>{pageNumber}</span>
        <button
          type="button"
          className="rounded border px-3 py-1"
          onClick={() => onPageChange(pageNumber + 1)}
        >
          다음
        </button>
      </div>
    </section>
  );
}
