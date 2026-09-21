"use client";

import { useCallback, useRef, useState } from "react";
import { getAccessToken } from "@/lib/api";
import type { TranscriptPreviewEvent } from "@/types/api";

const SAMPLE_RATE = 16000;
/** 3초 분량을 모아서 보낸다 (SPEC §2.1-2: 3~5초 단위) */
const CHUNK_SAMPLES = SAMPLE_RATE * 3;

interface AudioRecorderProps {
  lectureId: number;
  /** 녹음이 시작되어 강의 상태가 RECORDING 으로 바뀐 뒤 호출 */
  onRecordingStarted?: () => void;
  /** 녹음이 끝나 서버가 WAV 를 만든 뒤 호출 */
  onRecordingFinished?: () => void;
}

interface RecorderHandles {
  socket: WebSocket;
  context: AudioContext;
  stream: MediaStream;
  /** 아직 3초를 못 채운 잔여 PCM 을 마저 보낸다 */
  flush: () => void;
}

/**
 * Web Audio API 기반 강의 녹음 컨트롤러 및 실시간 자막 프리뷰 (SPEC §4.3).
 *
 * 마이크 → AudioWorklet(PCM 변환) → WebSocket → Spring Boot → FastAPI(STT) → 자막 수신.
 */
export default function AudioRecorder({
  lectureId,
  onRecordingStarted,
  onRecordingFinished,
}: AudioRecorderProps) {
  const [isRecording, setIsRecording] = useState(false);
  // 실시간 자막은 녹음 내내 Whisper 를 돌려 CPU 를 계속 쓴다(발열). 기본은 꺼 둔다.
  // 꺼도 녹음은 그대로 저장되고, 정밀 분석 품질에는 영향이 없다.
  const [subtitleEnabled, setSubtitleEnabled] = useState(false);
  const [preview, setPreview] = useState<TranscriptPreviewEvent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const handles = useRef<RecorderHandles | null>(null);

  const stop = useCallback(() => {
    const current = handles.current;
    handles.current = null;
    setIsRecording(false);
    if (!current) {
      return;
    }
    current.flush();
    current.stream.getTracks().forEach((track) => track.stop());
    void current.context.close();
    current.socket.close();
    onRecordingFinished?.();
  }, [onRecordingFinished]);

  const start = useCallback(async () => {
    setError(null);
    try {
      const token = getAccessToken();
      if (!token) {
        throw new Error("로그인이 필요합니다.");
      }
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      });
      const context = new AudioContext({ sampleRate: SAMPLE_RATE });
      await context.audioWorklet.addModule("/pcm-worklet.js");

      const base = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
      const socket = new WebSocket(
        `${base.replace(/^http/, "ws")}/ws/v1/lectures/${lectureId}/audio` +
          `?token=${encodeURIComponent(token)}&preview=${subtitleEnabled}`,
      );
      socket.binaryType = "arraybuffer";
      socket.onmessage = (event: MessageEvent<string>) =>
        setPreview(JSON.parse(event.data) as TranscriptPreviewEvent);
      socket.onerror = () => setError("녹음 연결에 실패했습니다.");
      socket.onclose = () => stop();

      const source = context.createMediaStreamSource(stream);
      const worklet = new AudioWorkletNode(context, "pcm-recorder");
      let pending: Int16Array[] = [];
      let pendingSamples = 0;

      const send = () => {
        if (pendingSamples === 0 || socket.readyState !== WebSocket.OPEN) {
          return;
        }
        const merged = new Int16Array(pendingSamples);
        let offset = 0;
        for (const part of pending) {
          merged.set(part, offset);
          offset += part.length;
        }
        pending = [];
        pendingSamples = 0;
        socket.send(merged.buffer);
      };

      worklet.port.onmessage = (event: MessageEvent<Int16Array>) => {
        pending.push(event.data);
        pendingSamples += event.data.length;
        if (pendingSamples >= CHUNK_SAMPLES) {
          send();
        }
      };

      source.connect(worklet);
      // 워클릿 출력을 쓰지 않지만, 연결해 두지 않으면 일부 브라우저가 처리를 멈춘다
      worklet.connect(context.destination);

      handles.current = { socket, context, stream, flush: send };
      setIsRecording(true);
      // 서버가 강의 상태를 RECORDING 으로 바꾸므로 화면을 갱신한다
      socket.onopen = () => onRecordingStarted?.();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "녹음을 시작하지 못했습니다.");
    }
  }, [lectureId, stop, onRecordingStarted, subtitleEnabled]);

  return (
    <section className="flex items-center gap-3">
      <button
        type="button"
        className={`rounded px-3 py-1 text-sm text-white ${isRecording ? "bg-zinc-700" : "bg-red-600"}`}
        onClick={() => (isRecording ? stop() : void start())}
      >
        {isRecording ? "녹음 종료" : "녹음 시작"}
      </button>
      <label
        className="flex items-center gap-1 text-xs text-zinc-500"
        title="켜면 녹음 중 노트북이 뜨거워지고 배터리를 더 씁니다. 꺼도 녹음 후 정밀 분석 품질은 같습니다."
      >
        <input
          type="checkbox"
          checked={subtitleEnabled}
          disabled={isRecording}
          onChange={(event) => setSubtitleEnabled(event.target.checked)}
        />
        실시간 자막
      </label>
      <p className="max-w-lg truncate text-sm text-zinc-600" aria-live="polite">
        {error ??
          (subtitleEnabled
            ? (preview?.text ?? (isRecording ? "듣는 중..." : "실시간 자막 프리뷰"))
            : isRecording
              ? "녹음 중 (자막 꺼짐)"
              : "")}
      </p>
    </section>
  );
}
