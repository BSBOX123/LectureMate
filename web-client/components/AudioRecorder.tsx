"use client";

import { useCallback, useRef, useState } from "react";
import { getAccessToken, recordingApi } from "@/lib/api";

const SAMPLE_RATE = 16000;
/** 3초 분량을 모아서 보낸다 (SPEC §2.1-8) */
const CHUNK_SAMPLES = SAMPLE_RATE * 3;

interface AudioRecorderProps {
  courseId: number;
  /** 녹음이 시작되었을 때 (목록 갱신용) */
  onRecordingStarted?: () => void;
  /** 녹음이 끝나 서버가 WAV 를 만들고 전사를 시작한 뒤 */
  onRecordingFinished?: () => void;
}

interface RecorderHandles {
  socket: WebSocket;
  context: AudioContext;
  stream: MediaStream;
  /** 아직 3초를 못 채운 잔여 PCM 을 마저 보낸다 */
  flush: () => void;
}

/** "10월 2일 수업" — 녹음 이름 기본값. 사용자가 매번 이름을 짓지 않아도 되게 한다. */
function defaultTitle(): string {
  const now = new Date();
  return `${now.getMonth() + 1}월 ${now.getDate()}일 수업`;
}

/**
 * Web Audio API 기반 녹음 컨트롤러 (SPEC §4.3, §2.1-7·8).
 *
 * 마이크 → AudioWorklet(PCM 변환) → WebSocket → Spring Boot(파일로 누적).
 * 녹음 중에는 어떤 모델도 돌리지 않는다. 전사는 녹음이 끝난 뒤 서버가 자동으로 시작한다.
 */
export default function AudioRecorder({
  courseId,
  onRecordingStarted,
  onRecordingFinished,
}: AudioRecorderProps) {
  const [isRecording, setIsRecording] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
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
    setStatus("녹음을 저장하고 전사를 시작했습니다.");
    onRecordingFinished?.();
  }, [onRecordingFinished]);

  const start = useCallback(async () => {
    setError(null);
    setStatus(null);
    try {
      const token = getAccessToken();
      if (!token) {
        throw new Error("로그인이 필요합니다.");
      }
      // 녹음할 자리를 먼저 만들어야 WebSocket 을 열 수 있다 (SPEC §2.1-7)
      const recording = await recordingApi.create(courseId, defaultTitle());

      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      });
      const context = new AudioContext({ sampleRate: SAMPLE_RATE });
      await context.audioWorklet.addModule("/pcm-worklet.js");

      const base = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
      const socket = new WebSocket(
        `${base.replace(/^http/, "ws")}/ws/v1/recordings/${recording.recordingId}/audio` +
          `?token=${encodeURIComponent(token)}`,
      );
      socket.binaryType = "arraybuffer";
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
      socket.onopen = () => {
        setStatus(`"${recording.title}" 녹음 중`);
        onRecordingStarted?.();
      };
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "녹음을 시작하지 못했습니다.");
    }
  }, [courseId, stop, onRecordingStarted]);

  return (
    <section className="flex items-center gap-3">
      <button
        type="button"
        className={`rounded px-3 py-1 text-sm text-white ${isRecording ? "bg-zinc-700" : "bg-red-600"}`}
        onClick={() => (isRecording ? stop() : void start())}
      >
        {isRecording ? "녹음 종료" : "녹음 시작"}
      </button>
      <p className="max-w-lg truncate text-sm text-zinc-600" aria-live="polite">
        {error ?? status ?? ""}
      </p>
    </section>
  );
}
