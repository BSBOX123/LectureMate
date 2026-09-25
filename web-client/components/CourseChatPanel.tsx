"use client";

import { useRef, useState } from "react";
import { courseApi } from "@/lib/api";
import type { ChatTurn, Citation } from "@/types/api";

interface CourseChatPanelProps {
  courseId: number;
  /** 자료 출처 뱃지를 누르면 그 자료의 해당 쪽으로 이동한다 */
  onCitationClick: (materialId: number, pageNumber: number) => void;
}

interface Message {
  role: "user" | "assistant";
  text: string;
  citations?: Citation[];
}

/** 프롬프트로 보낼 이전 대화 수. 많이 보내면 답변이 느려진다 (서버도 4마디로 자른다). */
const HISTORY_TURNS = 4;

/** 1043880 → "17:23" */
function timestamp(startTimeMs: number): string {
  const totalSeconds = Math.floor(startTimeMs / 1000);
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, "0")}`;
}

/** 출처 뱃지 한 줄. 자료면 "2장 SQL 14쪽", 녹음이면 "10월 2일 수업 17:23". */
function citationLabel(citation: Citation): string {
  if (citation.source === "MATERIAL") {
    return `${citation.materialTitle ?? "자료"} ${citation.pageNumber ?? "?"}쪽`;
  }
  return `${citation.recordingTitle ?? "녹음"} ${
    citation.startTimeMs !== null && citation.startTimeMs !== undefined
      ? timestamp(citation.startTimeMs)
      : ""
  }`;
}

/**
 * 과목 단위 RAG 어시스턴트 대화창 (SPEC §4.3, §2.1-11).
 *
 * 한 과목의 모든 자료와 녹음이 검색 대상이다. 출처(citations)가 토큰보다 먼저 오므로,
 * 답변이 만들어지는 동안 근거 뱃지를 먼저 보여 준다.
 */
export default function CourseChatPanel({ courseId, onCitationClick }: CourseChatPanelProps) {
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
    // 이번 질문을 넣기 전의 대화가 맥락이다. 빈 답변(생성 실패)은 보내지 않는다
    const history: ChatTurn[] = messages
      .filter((message) => message.text.trim().length > 0)
      .slice(-HISTORY_TURNS)
      .map((message) => ({ role: message.role, text: message.text }));
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
      await courseApi.chat(
        courseId,
        asked,
        history,
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
          <p className="text-zinc-500">
            이 과목의 자료와 녹음을 모두 찾아서 답합니다. 교수님이 말씀하신 내용도 함께 알려 주고,
            이어서 묻는 질문은 앞의 대화를 기억합니다.
          </p>
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
              {message.text || (streaming && index === messages.length - 1 ? "찾는 중..." : "")}
            </p>
            {message.citations && message.citations.length > 0 && (
              <div className="mt-1 flex flex-wrap gap-1">
                {message.citations.map((citation, citationIndex) => {
                  const isMaterial = citation.source === "MATERIAL";
                  const canNavigate =
                    isMaterial &&
                    citation.materialId !== null &&
                    citation.materialId !== undefined &&
                    citation.pageNumber !== null &&
                    citation.pageNumber !== undefined;
                  return (
                    <button
                      key={citationIndex}
                      type="button"
                      title={citation.snippet}
                      disabled={!canNavigate}
                      className={`rounded px-2 py-0.5 text-xs ${
                        isMaterial
                          ? "bg-amber-100 text-amber-900"
                          : "bg-sky-100 text-sky-900"
                      } disabled:cursor-default`}
                      onClick={() =>
                        canNavigate && onCitationClick(citation.materialId!, citation.pageNumber!)
                      }
                    >
                      {citationLabel(citation)}
                    </button>
                  );
                })}
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
