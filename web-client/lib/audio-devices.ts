/**
 * 녹음에 쓸 마이크 선택.
 *
 * macOS 시스템 설정에서 내장 마이크를 기본으로 바꿔도 **Chrome 은 자기 기준으로 장치를 고른다.**
 * 그래서 녹음을 시작할 때마다 아이폰(연속성 마이크)이 켜져 수업 중에 알림음이 크게 울렸고,
 * 아이폰이 멀어지면 스트림이 끊겨 61분을 무음으로 녹음한 사고까지 있었다 (Step 24).
 *
 * `getUserMedia` 에 `deviceId: { exact }` 를 주면 Chrome 의 선택을 무시하고 그 장치를 쓴다.
 */

const STORAGE_KEY = "lecturemate.microphoneId";

export interface Microphone {
  deviceId: string;
  label: string;
}

/**
 * 내장 마이크로 보이는 이름들.
 *
 * 기억해 둔 선택이 없을 때 아이폰 대신 내장을 고르기 위한 휴리스틱이다. 못 찾으면 목록의 첫
 * 장치를 쓰므로 틀려도 크게 문제되지 않는다.
 */
const BUILT_IN_HINTS = ["macbook", "내장", "built-in", "imac", "mac mini"];

export function isBuiltInMicrophone(label: string): boolean {
  const lower = label.toLowerCase();
  return BUILT_IN_HINTS.some((hint) => lower.includes(hint));
}

/**
 * 쓸 마이크를 정한다.
 *
 * 1. 지난번에 고른 장치가 아직 있으면 그것
 * 2. 없으면 내장으로 보이는 장치 (아이폰이 잡히는 것을 막는다)
 * 3. 그것도 없으면 첫 장치
 */
export function preferredMicrophone(
  devices: Microphone[],
  rememberedId: string | null,
): Microphone | null {
  if (devices.length === 0) {
    return null;
  }
  const remembered = devices.find((device) => device.deviceId === rememberedId);
  if (remembered) {
    return remembered;
  }
  return devices.find((device) => isBuiltInMicrophone(device.label)) ?? devices[0];
}

/**
 * 연결된 마이크 목록.
 *
 * 이름(label)은 마이크 권한이 있어야 보인다. 권한이 없으면 빈 문자열이 오므로 순서로 이름을
 * 붙인다 — 권한을 얻으려고 여기서 스트림을 열지는 않는다. 그러면 지금 문제인 "아이폰이 켜지는
 * 것" 을 그대로 일으킨다.
 */
export async function listMicrophones(): Promise<Microphone[]> {
  if (typeof navigator === "undefined" || !navigator.mediaDevices?.enumerateDevices) {
    return [];
  }
  const devices = await navigator.mediaDevices.enumerateDevices();
  return devices
    .filter((device) => device.kind === "audioinput")
    .map((device, index) => ({
      deviceId: device.deviceId,
      label: device.label || `마이크 ${index + 1}`,
    }));
}

export function rememberedMicrophoneId(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    // 시크릿 창 등에서 localStorage 가 막혀 있어도 녹음은 되어야 한다
    return null;
  }
}

export function rememberMicrophoneId(deviceId: string): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, deviceId);
  } catch {
    // 기억하지 못해도 이번 녹음에는 영향이 없다
  }
}
