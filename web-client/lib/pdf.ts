import type { PDFDocumentLoadingTask } from "pdfjs-dist";
import type { BBox } from "@/types/api";

/** 오버레이 하이라이트의 화면 좌표(px). */
export interface OverlayRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * PDF 포인트 좌표(bbox)를 렌더링된 페이지 위의 CSS 픽셀 좌표로 바꾼다.
 *
 * PyMuPDF와 PDF.js의 기본 뷰포트는 모두 좌상단 원점이라 y 를 뒤집을 필요가 없다.
 */
export function toOverlayRect(bbox: BBox, scale: number): OverlayRect {
  const [x1, y1, x2, y2] = bbox;
  return {
    left: x1 * scale,
    top: y1 * scale,
    width: (x2 - x1) * scale,
    height: (y2 - y1) * scale,
  };
}

/** 페이지 번호를 1..totalPages 범위로 보정한다. totalPages 를 모르면 1 이상만 보장한다. */
export function clampPage(pageNumber: number, totalPages: number | null): number {
  const lowerBounded = Math.max(1, Math.trunc(pageNumber));
  return totalPages ? Math.min(lowerBounded, totalPages) : lowerBounded;
}

/**
 * PDF.js 는 브라우저에서만 동작하므로 동적 import 로 불러온다.
 *
 * 반환한 loading task 는 언마운트 시 `destroy()` 로 워커까지 정리해야 한다
 * (PDFDocumentProxy 에는 destroy 가 없다).
 */
export async function createPdfLoadingTask(url: string): Promise<PDFDocumentLoadingTask> {
  const pdfjs = await import("pdfjs-dist");
  pdfjs.GlobalWorkerOptions.workerSrc = new URL(
    "pdfjs-dist/build/pdf.worker.min.mjs",
    import.meta.url,
  ).toString();
  return pdfjs.getDocument({ url });
}
