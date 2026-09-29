import { describe, expect, it } from "vitest";
import {
  pcmRms,
  rmsToMeter,
  shouldWarnSilence,
  SILENCE_RMS,
  SILENCE_WARN_MS,
} from "@/lib/audio-level";

/** 61분짜리 수업이 통째로 무음으로 녹음된 사고를 막기 위한 계산들. */
describe("마이크 입력 레벨", () => {
  it("무음 PCM 은 RMS 0 이다 (실제 사고 상황)", () => {
    // 아이폰 연속성 마이크가 끊겼을 때 들어오던 값
    expect(pcmRms(new Int16Array(1600))).toBe(0);
    expect(pcmRms(new Int16Array(0))).toBe(0);
  });

  it("실제 말소리 수준이면 무음 기준을 넘는다", () => {
    // 정상 녹음의 RMS 는 16bit 기준 1500~2000 근처였다 (실측)
    const speech = Int16Array.from({ length: 1600 }, (_, i) =>
      Math.round(2000 * Math.sin(i / 4)),
    );

    expect(pcmRms(speech)).toBeGreaterThan(SILENCE_RMS);
  });

  it("아주 작은 잡음도 완전 무음과 구분한다", () => {
    const faint = Int16Array.from({ length: 1600 }, (_, i) => (i % 2 === 0 ? 30 : -30));

    expect(pcmRms(faint)).toBeGreaterThan(SILENCE_RMS);
  });

  it("최대 진폭이면 RMS 가 1 에 가깝다", () => {
    const loud = new Int16Array(100).fill(32767);

    expect(pcmRms(loud)).toBeCloseTo(1, 2);
  });

  it("막대 길이는 0~1 을 벗어나지 않는다", () => {
    expect(rmsToMeter(0)).toBe(0);
    expect(rmsToMeter(1)).toBe(1);
    expect(rmsToMeter(2)).toBe(1); // 이론상 불가능하지만 막아 둔다
    expect(rmsToMeter(1e-9)).toBe(0);
  });

  it("말소리 구간에서 막대가 실제로 움직인다", () => {
    // RMS 를 그대로 쓰면 0.06 → 막대가 거의 안 보인다. dB 로 펼쳐야 한다
    const quiet = rmsToMeter(0.01);
    const normal = rmsToMeter(0.06);

    expect(normal).toBeGreaterThan(quiet + 0.1);
    expect(normal).toBeGreaterThan(0.5);
  });

  it("무음이 10초 넘게 이어져야 경고한다", () => {
    // 말하다 쉬는 정도로는 뜨지 않아야 한다
    expect(shouldWarnSilence(0)).toBe(false);
    expect(shouldWarnSilence(3000)).toBe(false);
    expect(shouldWarnSilence(SILENCE_WARN_MS - 1)).toBe(false);
    expect(shouldWarnSilence(SILENCE_WARN_MS)).toBe(true);
    expect(shouldWarnSilence(61 * 60 * 1000)).toBe(true);
  });
});
