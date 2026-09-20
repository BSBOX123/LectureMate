"use client";

import { useState } from "react";

interface LectureChatPanelProps {
  lectureId: number;
  /** 답변 출처 슬라이드 번호 뱃지 클릭 시 해당 슬라이드로 이동 */
  onCitationClick: (pageNumber: number) => void;
}

/**
 * SSE 기반 RAG 어시스턴트 대화창.
 *
 * TODO: POST /api/v1/lectures/{lectureId}/chat (§2.1-5) SSE 토큰 스트리밍 표시,
 * citations 를 슬라이드 번호 뱃지로 렌더링하고 클릭 시 onCitationClick 호출.
 */
export default function LectureChatPanel({
  lectureId,
  onCitationClick,
}: LectureChatPanelProps) {
  const [question, setQuestion] = useState("");

  return (
    <section className="flex h-full flex-col" data-lecture-id={lectureId}>
      <div className="flex-1 overflow-y-auto p-2 text-sm text-zinc-500">
        강의 내용에 대해 질문해 보세요.
        {/* 출처 뱃지 자리 표시 */}
        <button
          type="button"
          className="ml-2 hidden rounded bg-amber-100 px-2 text-xs"
          onClick={() => onCitationClick(1)}
        >
          p.1
        </button>
      </div>
      <form
        className="flex gap-2 border-t p-2"
        onSubmit={(event) => {
          event.preventDefault();
          setQuestion("");
        }}
      >
        <input
          className="flex-1 rounded border px-2 py-1 text-sm"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="질문 입력"
        />
        <button type="submit" className="rounded border px-3 py-1 text-sm">
          전송
        </button>
      </form>
    </section>
  );
}
