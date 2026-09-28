"use client";

import { useState } from "react";
import { recordingApi } from "@/lib/api";
import type { RecordingResponse } from "@/types/api";

interface RecordingListProps {
  courseId: number;
  recordings: RecordingResponse[];
  onChanged: () => void;
}

/** 36분 강의면 "36분". 전사가 끝나야 길이를 알 수 있다. */
function durationLabel(durationMs: number | null): string | null {
  if (durationMs === null || durationMs === 0) {
    return null;
  }
  return `${Math.round(durationMs / 60000)}분`;
}

function statusLabel(recording: RecordingResponse): string {
  switch (recording.status) {
    case "CREATED":
      return "준비됨";
    case "RECORDING":
      return "녹음 중";
    case "UPLOADED":
      // 전사는 자동으로 시작하지 않는다. 눌러야 한다는 것을 문구로 알려 준다
      return "전사하면 질문할 수 있어요";
    case "ANALYZING":
      // 실측 음성 1분당 약 34초. 멈춘 것처럼 보이지 않게 예상 시간을 알려 준다
      return "글로 옮기는 중 (음성 1분당 약 34초)";
    case "READY":
      return durationLabel(recording.durationMs) ?? "완료";
    case "FAILED":
      return "실패";
  }
}

/** 과목에 속한 녹음 목록 (SPEC §2.1-7 ~ §2.1-10). */
export default function RecordingList({
  courseId,
  recordings,
  onChanged,
}: RecordingListProps) {
  const [error, setError] = useState<string | null>(null);

  const remove = (recording: RecordingResponse) => {
    if (!window.confirm(`"${recording.title}" 녹음을 삭제할까요?\n전사 결과도 함께 지워집니다.`)) {
      return;
    }
    void recordingApi
      .remove(courseId, recording.recordingId)
      .then(onChanged)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "삭제하지 못했습니다."),
      );
  };

  const transcribe = (recording: RecordingResponse) => {
    setError(null);
    void recordingApi
      .transcribe(courseId, recording.recordingId)
      .then(onChanged)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "전사를 시작하지 못했습니다."),
      );
  };

  return (
    <section className="flex min-h-0 flex-col gap-1">
      <h2 className="text-xs font-semibold text-zinc-500">녹음</h2>
      <ul className="flex min-h-0 flex-col gap-1 overflow-y-auto">
        {recordings.length === 0 && (
          <li className="py-1 text-xs text-zinc-400">
            아직 녹음이 없습니다. 수업 중에 녹음하면 교수님 말씀도 함께 검색됩니다.
          </li>
        )}
        {recordings.map((recording) => (
          <li key={recording.recordingId} className="group flex items-start gap-1">
            <div className="min-w-0 flex-1 px-2 py-1">
              <p className="truncate text-xs" title={recording.title}>
                {recording.title}
              </p>
              <p
                className={`text-[11px] ${
                  recording.status === "FAILED" ? "text-red-600" : "text-zinc-400"
                }`}
              >
                {statusLabel(recording)}
              </p>
            </div>
            {(recording.status === "UPLOADED" || recording.status === "FAILED") && (
              <button
                type="button"
                // 전사는 사용자가 눌러야 시작되므로 주요 동작으로 보이게 한다
                className={`mt-1 shrink-0 rounded px-2 py-1 text-xs ${
                  recording.status === "UPLOADED"
                    ? "bg-amber-600 font-medium text-white"
                    : "border border-red-300 text-red-700"
                }`}
                onClick={() => transcribe(recording)}
              >
                {recording.status === "UPLOADED" ? "전사 시작" : "다시 시도"}
              </button>
            )}
            <button
              type="button"
              className="px-1 pt-1 text-xs text-zinc-300 hover:text-red-600 group-hover:text-zinc-500"
              title="삭제"
              onClick={() => remove(recording)}
            >
              ×
            </button>
          </li>
        ))}
      </ul>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </section>
  );
}
