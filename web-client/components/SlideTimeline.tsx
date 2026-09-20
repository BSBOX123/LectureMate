"use client";

export interface SlideTimelineItem {
  pageNumber: number;
  /** 해당 슬라이드에 매핑된 교수님 발화 총 길이 */
  speechDurationMs: number;
  hasExamHint: boolean;
}

interface SlideTimelineProps {
  /** PDF 총 페이지 수. 아직 모르면 null */
  totalPages: number | null;
  /** 분석 결과. 아직 없으면 빈 배열 */
  items: SlideTimelineItem[];
  currentPage: number;
  onSelectPage: (pageNumber: number) => void;
}

/** 슬라이드별 교수님 발화 분량 및 시험 힌트 유무 인디케이터 (SPEC §4.3). */
export default function SlideTimeline({
  totalPages,
  items,
  currentPage,
  onSelectPage,
}: SlideTimelineProps) {
  if (!totalPages) {
    return <p className="p-2 text-xs text-zinc-500">슬라이드를 불러오는 중입니다</p>;
  }

  const byPage = new Map(items.map((item) => [item.pageNumber, item]));
  const longestSpeech = Math.max(1, ...items.map((item) => item.speechDurationMs));

  return (
    <nav className="flex flex-col gap-1 overflow-y-auto">
      {Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => {
        const item = byPage.get(pageNumber);
        const ratio = item ? item.speechDurationMs / longestSpeech : 0;
        return (
          <button
            key={pageNumber}
            type="button"
            aria-current={pageNumber === currentPage}
            className={`flex items-center gap-2 rounded px-2 py-1 text-left text-sm ${
              pageNumber === currentPage ? "bg-zinc-200" : "hover:bg-zinc-100"
            }`}
            onClick={() => onSelectPage(pageNumber)}
          >
            <span className="w-6 shrink-0 tabular-nums">{pageNumber}</span>
            <span className="h-1.5 flex-1 rounded bg-zinc-200">
              <span
                className="block h-full rounded bg-zinc-500"
                style={{ width: `${Math.round(ratio * 100)}%` }}
              />
            </span>
            <span className="w-3 shrink-0 text-amber-500" title={item?.hasExamHint ? "시험 힌트" : ""}>
              {item?.hasExamHint ? "★" : ""}
            </span>
          </button>
        );
      })}
    </nav>
  );
}
