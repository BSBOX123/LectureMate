"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getAccessToken, recordingApi } from "@/lib/api";
import { pcmRms, rmsToMeter, shouldWarnSilence, SILENCE_RMS } from "@/lib/audio-level";
import {
  listMicrophones,
  preferredMicrophone,
  rememberedMicrophoneId,
  rememberMicrophoneId,
  type Microphone,
} from "@/lib/audio-devices";

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

/** 입력 레벨을 화면에 반영하는 주기. 워클릿 메시지마다 setState 하면 렌더가 과해진다. */
const METER_INTERVAL_MS = 100;

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

  // 입력 레벨과 무음 지속 시간. 워클릿이 아주 자주 부르므로 ref 에 모아 두고
  // 주기적으로만 화면에 반영한다.
  const levelRef = useRef(0);
  // 렌더 중에 Date.now() 를 부르면 안 되므로 0 으로 두고 녹음을 시작할 때 채운다
  const lastSoundAtRef = useRef(0);
  const [meter, setMeter] = useState(0);
  const [silentForMs, setSilentForMs] = useState(0);

  // 쓸 마이크. macOS 기본 입력과 별개로 Chrome 이 장치를 고르기 때문에 직접 지정해야 한다
  // (지정하지 않으면 아이폰 연속성 마이크가 켜져 수업 중에 알림음이 울렸다)
  const [microphones, setMicrophones] = useState<Microphone[]>([]);
  const [microphoneId, setMicrophoneId] = useState<string | null>(null);

  const loadMicrophones = useCallback(() => {
    void listMicrophones().then((found) => {
      setMicrophones(found);
      setMicrophoneId((current) => {
        const keep = current && found.some((device) => device.deviceId === current);
        return keep ? current : (preferredMicrophone(found, rememberedMicrophoneId())?.deviceId ?? null);
      });
    });
  }, []);

  // 목록은 장치가 바뀔 때마다 갱신한다 (에어팟 연결, 아이폰 연결 등)
  useEffect(() => {
    loadMicrophones();
    const devices = navigator.mediaDevices;
    devices?.addEventListener?.("devicechange", loadMicrophones);
    return () => devices?.removeEventListener?.("devicechange", loadMicrophones);
  }, [loadMicrophones]);

  useEffect(() => {
    if (!isRecording) {
      return;
    }
    const timer = setInterval(() => {
      setMeter(rmsToMeter(levelRef.current));
      setSilentForMs(Date.now() - lastSoundAtRef.current);
    }, METER_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [isRecording]);

  const stop = useCallback(() => {
    const current = handles.current;
    handles.current = null;
    setIsRecording(false);
    levelRef.current = 0;
    setMeter(0);
    setSilentForMs(0);
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
      // 녹음할 자리를 먼저 만들어야 WebSocket 을 열 수 있다 (SPEC §2.1-7).
      // 이 호출이 401 이면 api 계층이 토큰을 재발급하므로, 토큰은 **이 뒤에** 읽어야 한다.
      // (먼저 읽으면 만료된 토큰으로 WebSocket 을 열어 1008 로 끊긴다)
      const recording = await recordingApi.create(courseId, defaultTitle());
      const token = getAccessToken();
      if (!token) {
        throw new Error("로그인이 필요합니다.");
      }

      // deviceId 를 주지 않으면 Chrome 이 자기 기준으로 고른다 (아이폰이 켜지는 원인).
      // exact 로 지정하면 그 장치가 없을 때 조용히 다른 것을 쓰지 않고 오류가 난다.
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          ...(microphoneId ? { deviceId: { exact: microphoneId } } : {}),
        },
      });
      // 권한을 처음 얻으면 그때부터 장치 이름이 보인다
      loadMicrophones();
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

      lastSoundAtRef.current = Date.now();
      levelRef.current = 0;
      setMeter(0);
      setSilentForMs(0);
      worklet.port.onmessage = (event: MessageEvent<Int16Array>) => {
        // 소리가 실제로 들어오는지 본다. 61분을 무음으로 녹음한 사고가 있었다 (audio-level 참고)
        const rms = pcmRms(event.data);
        levelRef.current = rms;
        if (rms > SILENCE_RMS) {
          lastSoundAtRef.current = Date.now();
        }

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
      const failed =
        cause instanceof Error && cause.name === "OverconstrainedError"
          ? "고른 마이크를 찾을 수 없습니다. 연결을 확인하고 다시 골라 주세요."
          : cause instanceof Error
            ? cause.message
            : "녹음을 시작하지 못했습니다.";
      setError(failed);
      loadMicrophones();
    }
  }, [courseId, stop, onRecordingStarted, microphoneId, loadMicrophones]);

  const chooseMicrophone = (deviceId: string) => {
    setMicrophoneId(deviceId);
    rememberMicrophoneId(deviceId);
  };

  const silent = isRecording && shouldWarnSilence(silentForMs);

  return (
    <section className="flex items-center gap-3">
      <button
        type="button"
        className={`rounded px-3 py-1 text-sm text-white ${isRecording ? "bg-zinc-700" : "bg-red-600"}`}
        onClick={() => (isRecording ? stop() : void start())}
      >
        {isRecording ? "녹음 종료" : "녹음 시작"}
      </button>

      {/* 녹음 중에는 장치를 바꿀 수 없다 (스트림을 다시 열어야 한다) */}
      <select
        className="max-w-44 rounded border px-2 py-1 text-xs text-zinc-700 disabled:opacity-50"
        title="녹음에 쓸 마이크. macOS 기본 입력과 별개로 지정합니다."
        value={microphoneId ?? ""}
        disabled={isRecording || microphones.length === 0}
        onChange={(event) => chooseMicrophone(event.target.value)}
      >
        {microphones.length === 0 && <option value="">마이크 없음</option>}
        {microphones.map((device) => (
          <option key={device.deviceId} value={device.deviceId}>
            {device.label}
          </option>
        ))}
      </select>

      {isRecording && (
        <div
          className="flex items-center gap-2"
          title="마이크 입력 레벨. 말할 때 막대가 움직여야 정상입니다."
        >
          <div className="h-2 w-24 overflow-hidden rounded bg-zinc-200">
            <div
              className={`h-full transition-[width] duration-100 ${
                silent ? "bg-red-500" : "bg-emerald-500"
              }`}
              style={{ width: `${Math.round(meter * 100)}%` }}
            />
          </div>
          <span className="text-xs text-zinc-400">입력</span>
        </div>
      )}

      <p
        className={`max-w-lg truncate text-sm ${silent ? "font-medium text-red-600" : "text-zinc-600"}`}
        aria-live="polite"
      >
        {error ??
          (silent
            ? `마이크에서 소리가 들어오지 않습니다 (${Math.floor(silentForMs / 1000)}초). 입력 장치를 확인하세요.`
            : (status ?? ""))}
      </p>
    </section>
  );
}
