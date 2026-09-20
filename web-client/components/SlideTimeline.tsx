"use client";

export interface SlideTimelineItem {
  pageNumber: number;
  /** 해당 슬라이드에 매핑된 교수님 발화 총 길이 */
  speechDurationMs: number;
  hasExamHint: boolean;
}

interface SlideTimelineProps {
  items: SlideTimelineItem[];
  currentPage: number;
  onSelectPage: (pageNumber: number) => void;
}

/**
 * 슬라이드별 교수님 발화 분량 및 시험 힌트 유무 인디케이터.
 *
 * TODO: 발화 분량을 막대 길이로 표시, 시험 힌트 아이콘 표시.
 */
export default function SlideTimeline({
  items,
  currentPage,
  onSelectPage,
}: SlideTimelineProps) {
  return (
    <nav className="flex flex-col gap-1 overflow-y-auto">
      {items.length === 0 && (
        <p className="p-2 text-xs text-zinc-500">분석된 슬라이드가 없습니다</p>
      )}
      {items.map((item) => (
        <button
          key={item.pageNumber}
          type="button"
          className={`rounded px-2 py-1 text-left text-sm ${
            item.pageNumber === currentPage ? "bg-zinc-200" : ""
          }`}
          onClick={() => onSelectPage(item.pageNumber)}
        >
          {item.pageNumber}
          {item.hasExamHint && " ★"}
        </button>
      ))}
    </nav>
  );
}
