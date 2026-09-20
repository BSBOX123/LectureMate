/** SSE(text/event-stream) 파서. fetch 스트림은 EventSource 를 쓸 수 없어 직접 해석한다. */

export interface SseEvent {
  event: string;
  data: string;
}

/**
 * 버퍼에서 완성된 이벤트만 꺼내고, 남은 조각을 돌려준다.
 * 이벤트는 빈 줄로 구분되므로 마지막 조각은 아직 미완성일 수 있다.
 */
export function parseSseBuffer(buffer: string): { events: SseEvent[]; rest: string } {
  const blocks = buffer.split("\n\n");
  const rest = blocks.pop() ?? "";
  const events: SseEvent[] = [];

  for (const block of blocks) {
    let event = "message";
    const dataLines: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) {
        event = line.slice("event:".length).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice("data:".length).trim());
      }
    }
    if (dataLines.length > 0) {
      events.push({ event, data: dataLines.join("\n") });
    }
  }
  return { events, rest };
}
