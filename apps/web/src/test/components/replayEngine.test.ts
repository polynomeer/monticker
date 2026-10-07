import { describe, expect, it } from "vitest";
import { advance, candleIndexAt, nextOrderIndex, stepFor } from "@/components/wallet/replayEngine";

const candles = [0, 60, 120, 180, 240].map((time) => ({ time }));

describe("replayEngine", () => {
  it("maps a time to the candle it falls in", () => {
    expect(candleIndexAt(candles, 0)).toBe(0);
    expect(candleIndexAt(candles, 119)).toBe(1);
    expect(candleIndexAt(candles, 999)).toBe(4);
    expect(candleIndexAt(candles, -5)).toBe(0);
    expect(candleIndexAt([], 10)).toBe(-1);
  });

  it("finds the next order after the cursor", () => {
    expect(nextOrderIndex(candles, [130, 30], -1)).toBe(0);
    expect(nextOrderIndex(candles, [130, 30], 0)).toBe(2);
    expect(nextOrderIndex(candles, [130, 30], 2)).toBeNull();
  });

  it("keeps redraws at most every 125ms and stops at the end", () => {
    expect(stepFor(1)).toEqual({ candles: 1, intervalMs: 500 });
    expect(stepFor(16)).toEqual({ candles: 4, intervalMs: 125 });
    expect(advance(3, 4, 5)).toBe(4);
  });
});
