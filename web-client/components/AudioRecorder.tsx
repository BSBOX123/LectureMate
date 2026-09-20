"use client";

import { useState } from "react";
import type { TranscriptPreviewEvent } from "@/types/api";

interface AudioRecorderProps {
  lectureId: number;
  /** §2.1-3 녹음 종료 및 분석 트리거 후 호출 */
  onRecordingFinished?: () => void;
}

/**
 * Web Audio API / MediaRecorder 기반 강의 녹음 컨트롤러 및 실시간 자막 프리뷰.
 *
 * TODO: 마이크 녹음 → WS /ws/v1/lectures/{lectureId}/audio 로 청크 전송(§2.1-2),
 * TRANSCRIPT_PREVIEW 수신 표시, 종료 시 POST /api/v1/lectures/{lectureId}/recording/finish(§2.1-3).
 */
export default function AudioRecorder({
  lectureId,
  onRecordingFinished,
}: AudioRecorderProps) {
  const [isRecording, setIsRecording] = useState(false);
  const [previews] = useState<TranscriptPreviewEvent[]>([]);

  const handleToggle = () => {
    if (isRecording) {
      onRecordingFinished?.();
    }
    setIsRecording(!isRecording);
  };

  return (
    <section className="flex items-center gap-3" data-lecture-id={lectureId}>
      <button
        type="button"
        className="rounded bg-red-600 px-3 py-1 text-sm text-white"
        onClick={handleToggle}
      >
        {isRecording ? "녹음 종료" : "녹음 시작"}
      </button>
      <p className="truncate text-sm text-zinc-600">
        {previews.at(-1)?.text ?? "실시간 자막 프리뷰"}
      </p>
    </section>
  );
}
