import { describe, expect, it } from "vitest";
import { kstTime } from "@/components/home/data";

describe("kstTime", () => {
  const now = new Date("2026-10-05T13:00:00Z"); // KST 10-05 22:00

  it("오늘(KST) 이벤트는 시각만 보여준다", () => {
    expect(kstTime("2026-10-05T01:24:00Z", now)).toBe("10:24");
  });

  it("이전 날짜 이벤트는 날짜를 붙인다", () => {
    expect(kstTime("2026-09-21T14:18:42Z", now)).toBe("09.21 23:18");
  });

  it("UTC로는 전날이어도 KST로 오늘이면 시각만", () => {
    expect(kstTime("2026-10-04T16:30:00Z", now)).toBe("01:30");
  });
});
