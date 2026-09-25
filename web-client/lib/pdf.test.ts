import { describe, expect, it } from "vitest";
import { clampPage } from "@/lib/pdf";

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
