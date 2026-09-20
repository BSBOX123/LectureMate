"use client";

import type { PDFDocumentLoadingTask, PDFDocumentProxy } from "pdfjs-dist";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { clampPage, createPdfLoadingTask } from "@/lib/pdf";

export interface RenderedPageInfo {
  /** PDF 포인트 → CSS 픽셀 배율. AnnotationOverlay 가 bbox 를 변환할 때 쓴다. */
  scale: number;
  width: number;
  height: number;
}

interface PdfViewerProps {
  /** blob URL 또는 접근 가능한 PDF URL. 아직 없으면 null */
  pdfUrl: string | null;
  pageNumber: number;
  onPageChange: (pageNumber: number) => void;
  onDocumentLoaded?: (totalPages: number) => void;
  onPageRendered?: (info: RenderedPageInfo) => void;
  /** 페이지 위에 겹쳐 그릴 레이어 (AnnotationOverlay) */
  children?: ReactNode;
}

/** PDF.js 기반 슬라이드 렌더러 (SPEC §4.3). 컨테이너 너비에 맞춰 페이지를 그린다. */
export default function PdfViewer({
  pdfUrl,
  pageNumber,
  onPageChange,
  onDocumentLoaded,
  onPageRendered,
  children,
}: PdfViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null);
  const [totalPages, setTotalPages] = useState<number | null>(null);
  const [containerWidth, setContainerWidth] = useState(0);
  const [pageSize, setPageSize] = useState<{ width: number; height: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 컨테이너 너비 추적 (창 크기 변경 대응)
  useEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver((entries) => {
      setContainerWidth(entries[0].contentRect.width);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // 문서 로드. pdfUrl 이 바뀌면 이전 문서와 워커를 정리한다.
  useEffect(() => {
    if (!pdfUrl) {
      return;
    }
    let cancelled = false;
    let task: PDFDocumentLoadingTask | null = null;
    void createPdfLoadingTask(pdfUrl)
      .then(async (loadingTask) => {
        task = loadingTask;
        const pdf = await loadingTask.promise;
        if (cancelled) {
          return;
        }
        setDocument(pdf);
        setTotalPages(pdf.numPages);
        onDocumentLoaded?.(pdf.numPages);
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "PDF를 열지 못했습니다.");
        }
      });
    return () => {
      cancelled = true;
      void task?.destroy();
    };
  }, [pdfUrl, onDocumentLoaded]);

  // 페이지 렌더링
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!document || !canvas || containerWidth === 0) {
      return;
    }
    let cancelled = false;
    let renderTask: { cancel: () => void } | null = null;

    void document.getPage(clampPage(pageNumber, totalPages)).then((page) => {
      if (cancelled) {
        return;
      }
      const base = page.getViewport({ scale: 1 });
      const scale = containerWidth / base.width;
      const viewport = page.getViewport({ scale });
      const ratio = window.devicePixelRatio || 1;

      canvas.width = Math.floor(viewport.width * ratio);
      canvas.height = Math.floor(viewport.height * ratio);
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;

      const context = canvas.getContext("2d");
      if (!context) {
        return;
      }
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      renderTask = page.render({ canvas, canvasContext: context, viewport });
      setPageSize({ width: viewport.width, height: viewport.height });
      onPageRendered?.({ scale, width: viewport.width, height: viewport.height });
    });

    return () => {
      cancelled = true;
      renderTask?.cancel();
    };
  }, [document, pageNumber, totalPages, containerWidth, onPageRendered]);

  const goTo = useCallback(
    (next: number) => onPageChange(clampPage(next, totalPages)),
    [onPageChange, totalPages],
  );

  return (
    <section className="flex h-full min-h-0 min-w-0 flex-col">
      <div
        ref={containerRef}
        className="flex min-h-0 flex-1 items-start justify-center overflow-auto rounded border border-zinc-200 bg-zinc-50"
      >
        <div className="relative" style={pageSize ?? undefined}>
          <canvas ref={canvasRef} className={pdfUrl ? "" : "hidden"} />
          {children}
          {!pdfUrl && (
            <p className="p-8 text-sm text-zinc-500">
              {error ?? "PDF를 불러오는 중입니다"}
            </p>
          )}
        </div>
      </div>
      <div className="flex items-center justify-center gap-4 py-2 text-sm">
        <button
          type="button"
          className="rounded border px-3 py-1 disabled:opacity-40"
          disabled={pageNumber <= 1}
          onClick={() => goTo(pageNumber - 1)}
        >
          이전
        </button>
        <span>
          {pageNumber}
          {totalPages ? ` / ${totalPages}` : ""}
        </span>
        <button
          type="button"
          className="rounded border px-3 py-1 disabled:opacity-40"
          disabled={totalPages !== null && pageNumber >= totalPages}
          onClick={() => goTo(pageNumber + 1)}
        >
          다음
        </button>
      </div>
    </section>
  );
}
