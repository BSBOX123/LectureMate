import { describe, expect, it } from "vitest";
import { clampPage, toOverlayRect } from "@/lib/pdf";

describe("toOverlayRect", () => {
  it("배율 1에서는 PDF 좌표를 그대로 쓴다", () => {
    expect(toOverlayRect([145.2, 310.5, 230.1, 328.0], 1)).toEqual({
      left: 145.2,
      top: 310.5,
      width: 84.9,
      height: 17.5,
    });
  });

  it("배율만큼 위치와 크기를 함께 확대한다", () => {
    const rect = toOverlayRect([100, 200, 150, 220], 2);
    expect(rect.left).toBe(200);
    expect(rect.top).toBe(400);
    expect(rect.width).toBe(100);
    expect(rect.height).toBe(40);
  });
});

describe("clampPage", () => {
  it("1보다 작은 값은 1로 올린다", () => {
    expect(clampPage(0, 10)).toBe(1);
    expect(clampPage(-5, 10)).toBe(1);
  });

  it("총 페이지 수를 넘지 않는다", () => {
    expect(clampPage(11, 10)).toBe(10);
    expect(clampPage(5, 10)).toBe(5);
  });

  it("총 페이지 수를 모르면 하한만 적용한다", () => {
    expect(clampPage(999, null)).toBe(999);
    expect(clampPage(0, null)).toBe(1);
  });
});
