import { describe, expect, it } from "vitest";
import { safeNext } from "@/services/consent";

describe("safeNext", () => {
  it("쿼리·해시도 그대로 둔다", () => {
    expect(safeNext("/consent?x=1#a")).toBe("/consent?x=1#a");
  });

  it("앱 안의 상대 경로는 그대로 둔다", () => {
    expect(safeNext("/brokerage/orders?symbol=005930")).toBe("/brokerage/orders?symbol=005930");
  });

  it.each(["https://evil.example", "//evil.example", "/\\evil.example", "/\t/evil.example", "/\n/evil.example", "/\r//evil.example", "javascript:alert(1)", "", null, undefined])(
    "외부로 나가는 값(%s)은 홈으로 바꾼다",
    (v) => {
      expect(safeNext(v)).toBe("/");
    },
  );
});
