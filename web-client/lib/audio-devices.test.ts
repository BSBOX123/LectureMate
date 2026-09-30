import { describe, expect, it } from "vitest";
import { isBuiltInMicrophone, preferredMicrophone, type Microphone } from "@/lib/audio-devices";

/** 실제 이 맥에 잡히는 장치들 (system_profiler 로 확인한 목록). */
const DEVICES: Microphone[] = [
  { deviceId: "iphone-1", label: "‘BXBOX’ 마이크" },
  { deviceId: "bgm-1", label: "Background Music" },
  { deviceId: "builtin-1", label: "MacBook Air 마이크" },
];

describe("마이크 선택", () => {
  it("내장 마이크를 알아본다", () => {
    expect(isBuiltInMicrophone("MacBook Air 마이크")).toBe(true);
    expect(isBuiltInMicrophone("내장 마이크")).toBe(true);
    expect(isBuiltInMicrophone("Built-in Microphone")).toBe(true);

    // 아이폰과 가상 오디오 장치는 내장이 아니다
    expect(isBuiltInMicrophone("‘BXBOX’ 마이크")).toBe(false);
    expect(isBuiltInMicrophone("Background Music")).toBe(false);
  });

  it("기억해 둔 선택이 있으면 그것을 쓴다", () => {
    expect(preferredMicrophone(DEVICES, "iphone-1")?.deviceId).toBe("iphone-1");
  });

  it("기억해 둔 장치가 사라졌으면 내장으로 넘어간다", () => {
    // 에어팟을 쓰다 빼면 그 deviceId 는 목록에 없다
    expect(preferredMicrophone(DEVICES, "airpods-gone")?.deviceId).toBe("builtin-1");
  });

  it("고른 적이 없으면 내장을 고른다 (아이폰이 잡히는 것을 막는다)", () => {
    // 이게 이번 문제의 핵심이다. Chrome 기본값은 아이폰이었다
    expect(preferredMicrophone(DEVICES, null)?.deviceId).toBe("builtin-1");
  });

  it("내장이 없으면 첫 장치를 쓴다", () => {
    const externalOnly: Microphone[] = [
      { deviceId: "usb-1", label: "USB Microphone" },
      { deviceId: "iphone-1", label: "‘BXBOX’ 마이크" },
    ];

    expect(preferredMicrophone(externalOnly, null)?.deviceId).toBe("usb-1");
  });

  it("장치가 없으면 null", () => {
    expect(preferredMicrophone([], null)).toBeNull();
    expect(preferredMicrophone([], "anything")).toBeNull();
  });
});
