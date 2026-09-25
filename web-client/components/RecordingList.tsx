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
      return "전사 대기";
    case "ANALYZING":
      // 실측 36분 강의에 약 15분. 멈춘 것처럼 보이지 않게 미리 알려 준다
      return "글로 옮기는 중 (36분 강의 기준 약 15분)";
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

  const retry = (recording: RecordingResponse) => {
    void recordingApi
      .retry(courseId, recording.recordingId)
      .then(onChanged)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "다시 시도하지 못했습니다."),
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
            {(recording.status === "FAILED" || recording.status === "UPLOADED") && (
              <button
                type="button"
                className="pt-1 text-xs text-blue-600"
                onClick={() => retry(recording)}
              >
                {recording.status === "FAILED" ? "재시도" : "전사"}
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
