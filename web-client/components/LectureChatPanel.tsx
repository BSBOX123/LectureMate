"use client";

import { useRef, useState } from "react";
import { lectureApi } from "@/lib/api";
import type { Citation } from "@/types/api";

interface LectureChatPanelProps {
  lectureId: number;
  /** 답변 출처 슬라이드 번호 뱃지 클릭 시 해당 슬라이드로 이동 */
  onCitationClick: (pageNumber: number) => void;
}

interface Message {
  role: "user" | "assistant";
  text: string;
  citations?: Citation[];
}

/**
 * SSE 기반 RAG 어시스턴트 대화창 (SPEC §4.3, §2.1-5).
 *
 * 출처(citations)가 토큰보다 먼저 오므로, 답변이 생성되는 동안 뱃지를 먼저 보여 준다.
 */
export default function LectureChatPanel({ lectureId, onCitationClick }: LectureChatPanelProps) {
  const [question, setQuestion] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const ask = async (event: React.FormEvent) => {
    event.preventDefault();
    const asked = question.trim();
    if (!asked || streaming) {
      return;
    }
    setQuestion("");
    setError(null);
    setStreaming(true);
    setMessages((previous) => [
      ...previous,
      { role: "user", text: asked },
      { role: "assistant", text: "", citations: [] },
    ]);

    const updateAnswer = (change: (message: Message) => Message) =>
      setMessages((previous) =>
        previous.map((message, index) =>
          index === previous.length - 1 ? change(message) : message,
        ),
      );

    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await lectureApi.chat(
        lectureId,
        asked,
        {
          onCitations: (citations) => updateAnswer((message) => ({ ...message, citations })),
          onToken: (text) =>
            updateAnswer((message) => ({ ...message, text: message.text + text })),
          onDone: (finishReason) => {
            if (finishReason === "error") {
              setError("답변 생성 중 오류가 발생했습니다.");
            }
          },
        },
        controller.signal,
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "답변을 받지 못했습니다.");
    } finally {
      setStreaming(false);
      abortRef.current = null;
    }
  };

  return (
    <section className="flex h-full min-h-0 flex-col border-l">
      <div className="flex-1 space-y-3 overflow-y-auto p-3 text-sm">
        {messages.length === 0 && (
          <p className="text-zinc-500">강의 내용에 대해 질문해 보세요.</p>
        )}
        {messages.map((message, index) => (
          <div key={index} className={message.role === "user" ? "text-right" : ""}>
            <p
              className={
                message.role === "user"
                  ? "inline-block rounded bg-zinc-900 px-3 py-1 text-white"
                  : "whitespace-pre-wrap text-zinc-800"
              }
            >
              {message.text || (streaming && index === messages.length - 1 ? "생각 중..." : "")}
            </p>
            {message.citations && message.citations.length > 0 && (
              <div className="mt-1 flex flex-wrap gap-1">
                {message.citations.map((citation, citationIndex) => (
                  <button
                    key={citationIndex}
                    type="button"
                    title={citation.snippet}
                    disabled={citation.pageNumber === null}
                    className="rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-900 disabled:opacity-50"
                    onClick={() =>
                      citation.pageNumber !== null && onCitationClick(citation.pageNumber)
                    }
                  >
                    {citation.source === "SLIDE" ? "슬라이드" : "발화"}{" "}
                    {citation.pageNumber !== null ? `p.${citation.pageNumber}` : "?"}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
        {error && <p className="text-sm text-red-600">{error}</p>}
      </div>
      <form className="flex gap-2 border-t p-2" onSubmit={ask}>
        <input
          className="flex-1 rounded border px-2 py-1 text-sm"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="질문 입력"
          disabled={streaming}
        />
        <button
          type="submit"
          className="rounded border px-3 py-1 text-sm disabled:opacity-50"
          disabled={streaming}
        >
          {streaming ? "답변 중" : "전송"}
        </button>
      </form>
    </section>
  );
}
