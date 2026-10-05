import { describe, expect, it } from "vitest";
import { MIN_BETA_POINTS, beta, kstDayKey } from "@/components/compare/beta";

function series(returns: number[], start = 100) {
  const m = new Map<string, number>();
  let v = start;
  const d0 = Date.UTC(2026, 0, 1);
  m.set(new Date(d0).toISOString().slice(0, 10), v);
  returns.forEach((r, i) => {
    v = v * (1 + r);
    m.set(new Date(d0 + (i + 1) * 86_400_000).toISOString().slice(0, 10), v);
  });
  return m;
}

describe("beta", () => {
  const idx = Array.from({ length: 40 }, (_, i) => ((i * 7) % 5 - 2) / 100);

  it("is 2 when the stock moves twice the index", () => {
    expect(beta(series(idx.map((r) => r * 2)), series(idx))).toBeCloseTo(2, 6);
  });

  it("uses only common trading days", () => {
    const stock = series(idx);
    const index = series(idx);
    const extra = new Map(stock);
    extra.set("2025-12-25", 999); // 지수에 없는 날짜
    expect(beta(extra, index)).toBeCloseTo(1, 6);
  });

  it("returns null with too few points or a flat index", () => {
    const few = idx.slice(0, MIN_BETA_POINTS - 1);
    expect(beta(series(few), series(few))).toBeNull();
    expect(beta(series(idx), series(idx.map(() => 0)))).toBeNull();
  });

  it("maps candle times at KST midnight to the KST date", () => {
    // 2026-10-05 00:00 KST = 2026-10-04T15:00Z
    expect(kstDayKey(Date.UTC(2026, 9, 4, 15) / 1000)).toBe("2026-10-05");
  });
});
