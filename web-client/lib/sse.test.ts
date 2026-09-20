import { describe, expect, it } from "vitest";
import { parseSseBuffer } from "@/lib/sse";

describe("parseSseBuffer", () => {
  it("완성된 이벤트만 꺼내고 나머지는 남긴다", () => {
    const { events, rest } = parseSseBuffer(
      'event: token\ndata: {"text":"다익"}\n\nevent: token\ndata: {"tex',
    );

    expect(events).toEqual([{ event: "token", data: '{"text":"다익"}' }]);
    expect(rest).toBe('event: token\ndata: {"tex');
  });

  it("여러 이벤트를 순서대로 돌려준다", () => {
    const { events } = parseSseBuffer(
      'event: citations\ndata: {"citations":[]}\n\nevent: done\ndata: {"finishReason":"stop"}\n\n',
    );

    expect(events.map((e) => e.event)).toEqual(["citations", "done"]);
    expect(events[1].data).toBe('{"finishReason":"stop"}');
  });

  it("event 줄이 없으면 message 로 본다", () => {
    const { events } = parseSseBuffer("data: hello\n\n");

    expect(events).toEqual([{ event: "message", data: "hello" }]);
  });

  it("빈 버퍼는 이벤트가 없다", () => {
    expect(parseSseBuffer("").events).toEqual([]);
  });
});
