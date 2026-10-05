import { describe, expect, it } from "vitest";
import { safeNext } from "@/services/consent";

describe("safeNext", () => {
  it("앱 안의 상대 경로는 그대로 둔다", () => {
    expect(safeNext("/brokerage/orders?symbol=005930")).toBe("/brokerage/orders?symbol=005930");
  });

  it.each(["https://evil.example", "//evil.example", "/\\evil.example", "javascript:alert(1)", "", null, undefined])(
    "외부로 나가는 값(%s)은 홈으로 바꾼다",
    (v) => {
      expect(safeNext(v)).toBe("/");
    },
  );
});
