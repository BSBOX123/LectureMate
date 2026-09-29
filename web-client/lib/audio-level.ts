/**
 * 마이크 입력 레벨 계산.
 *
 * 실사용에서 **61분짜리 수업이 통째로 무음으로 녹음됐다.** 맥이 아이폰을 연속성 마이크로 잡고
 * 있다가 끊긴 것인데, WebSocket 은 살아 있어서 0 으로 채워진 PCM 이 61분간 쌓였고 화면에는
 * 아무 경고도 없었다. 파일 크기·길이·헤더가 모두 정상이라 시스템은 "잘 녹음됐다" 고 판단했다.
 *
 * 실시간 자막을 뺄 때(Step 21) 화면의 피드백도 같이 사라진 것이 원인이다. 모델을 돌리지 않고
 * **진폭만 계산**하면 같은 사고를 막을 수 있다. 단순 산술이라 CPU 를 거의 쓰지 않는다.
 */

/** 이 값보다 작으면 사실상 무음. 16bit 기준 약 1/3000 로, 조용한 강의실 잡음보다도 작다. */
export const SILENCE_RMS = 0.0003;

/** 이 시간 동안 계속 무음이면 경고한다. 말하다 쉬는 정도로는 뜨지 않아야 한다. */
export const SILENCE_WARN_MS = 10_000;

/** 16bit PCM 한 덩어리의 RMS 를 0~1 로 돌려준다. */
export function pcmRms(pcm: Int16Array): number {
  if (pcm.length === 0) {
    return 0;
  }
  let sum = 0;
  for (let i = 0; i < pcm.length; i++) {
    const sample = pcm[i] / 32768;
    sum += sample * sample;
  }
  return Math.sqrt(sum / pcm.length);
}

/**
 * RMS 를 막대 길이(0~1)로 바꾼다.
 *
 * 사람이 느끼는 음량은 로그에 가까워서 RMS 를 그대로 쓰면 평소 말소리에서 막대가 거의 안 움직인다.
 * dB 로 바꿔 -60dB ~ 0dB 구간을 펼친다.
 */
export function rmsToMeter(rms: number): number {
  if (rms <= 0) {
    return 0;
  }
  const db = 20 * Math.log10(rms);
  return Math.min(1, Math.max(0, (db + 60) / 60));
}

/** 무음이 이어진 시간으로 경고를 낼지 정한다. */
export function shouldWarnSilence(silentForMs: number): boolean {
  return silentForMs >= SILENCE_WARN_MS;
}
