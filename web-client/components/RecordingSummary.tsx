"use client";

import { useEffect, useState } from "react";
import { recordingApi } from "@/lib/api";
import type { RecordingResponse, RecordingSummaryResponse } from "@/types/api";

interface RecordingSummaryProps {
  courseId: number;
  recording: RecordingResponse;
  /** 요약이 새로 만들어져 목록의 hasSummary 가 바뀌었을 때 */
  onCreated: () => void;
}

/**
 * 녹음 요약 보기 (SPEC §2.1-12).
 *
 * 채팅으로는 "교수님이 강조한 부분" 같은 질문에 답할 수 없다. 강조는 주제가 아니어서 검색으로
 * 찾을 수 없고, 한 시간짜리 수업을 조각 몇 개로 요약할 수 없다. 그래서 전사 전체를 한 번에
 * 읽히는 이 화면을 따로 둔다.
 */
export default function RecordingSummary({
  courseId,
  recording,
  onCreated,
}: RecordingSummaryProps) {
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 어느 녹음의 요약인지 함께 담아 둔다. 다른 녹음으로 옮기면 렌더링에서 걸러 내므로
  // effect 안에서 상태를 비울 필요가 없다 (불필요한 렌더도 줄어든다)
  const [loaded, setLoaded] = useState<RecordingSummaryResponse | null>(null);

  // 이미 만들어 둔 요약이 있으면 읽어 온다 (없으면 만들기 안내)
  useEffect(() => {
    if (!recording.hasSummary) {
      return;
    }
    let cancelled = false;
    void recordingApi
      .summary(courseId, recording.recordingId)
      .then((got) => !cancelled && setLoaded(got))
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "요약을 불러오지 못했습니다.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [courseId, recording.recordingId, recording.hasSummary]);

  const summary = loaded?.recordingId === recording.recordingId ? loaded : null;

  const create = () => {
    setCreating(true);
    setError(null);
    void recordingApi
      .createSummary(courseId, recording.recordingId)
      .then((created) => {
        setLoaded(created);
        onCreated();
      })
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "요약을 만들지 못했습니다."),
      )
      .finally(() => setCreating(false));
  };

  return (
    <section className="flex h-full min-h-0 min-w-0 flex-col rounded border border-zinc-200 bg-white">
      <header className="flex items-center justify-between gap-3 border-b px-4 py-2">
        <h2 className="truncate text-sm font-semibold">{recording.title} 요약</h2>
        <button
          type="button"
          className="shrink-0 rounded bg-zinc-900 px-3 py-1 text-xs text-white disabled:opacity-50"
          disabled={creating}
          onClick={create}
        >
          {creating ? "만드는 중..." : summary ? "다시 만들기" : "요약 만들기"}
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3 text-sm leading-relaxed">
        {creating && (
          <p className="text-zinc-500">
            전사 전체를 읽고 있습니다. 30초쯤 걸립니다.
          </p>
        )}
        {error && <p className="text-red-600">{error}</p>}
        {!creating && !error && summary === null && (
          <p className="text-zinc-500">
            아직 요약이 없습니다. 만들면 교수님이 강조한 내용과 시험 언급을
            시각과 함께 정리해 줍니다.
          </p>
        )}
        {summary && <p className="whitespace-pre-wrap text-zinc-800">{summary.summary}</p>}
      </div>

      {summary && (
        <footer className="border-t px-4 py-1 text-xs text-zinc-400">
          {new Date(summary.summarizedAt).toLocaleString("ko-KR")}에 만들어짐
        </footer>
      )}
    </section>
  );
}
