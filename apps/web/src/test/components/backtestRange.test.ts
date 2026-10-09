import { describe, expect, it } from "vitest";
import { defaultBacktestRange } from "@/lib/backtestRange";

describe("defaultBacktestRange", () => {
  it("ends yesterday in KST and starts the given number of days before", () => {
    // 2026-10-10 10:00 KST
    const now = Date.parse("2026-10-10T01:00:00Z");
    expect(defaultBacktestRange(730, now)).toEqual({ startDate: "2024-10-09", endDate: "2026-10-09" });
    expect(defaultBacktestRange(30, now)).toEqual({ startDate: "2026-09-09", endDate: "2026-10-09" });
  });

  it("uses the KST date even when the UTC date is still the previous day", () => {
    // 2026-10-10 00:30 KST = 2026-10-09 15:30 UTC
    const now = Date.parse("2026-10-09T15:30:00Z");
    expect(defaultBacktestRange(30, now).endDate).toBe("2026-10-09");
  });
});
