import { describe, expect, it } from "vitest";
import { applyMove, moveTarget, range52wLabels } from "@/components/watchlist/order";

const items = [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }];

describe("moveTarget", () => {
  it("moves one step within the full list", () => {
    const visible = [1, 2, 3, 4];
    expect(moveTarget(items, visible, 3, "up")).toBe(1);
    expect(moveTarget(items, visible, 3, "down")).toBe(3);
  });

  it("has nowhere to go at the edges", () => {
    const visible = [1, 2, 3, 4];
    expect(moveTarget(items, visible, 1, "up")).toBeNull();
    expect(moveTarget(items, visible, 4, "down")).toBeNull();
  });

  it("jumps over items hidden by a filter to the visible neighbour", () => {
    // 2, 3이 시장 필터로 숨음 — 4를 위로 올리면 1 자리(0)로
    const visible = [1, 4];
    expect(moveTarget(items, visible, 4, "up")).toBe(0);
    expect(moveTarget(items, visible, 1, "down")).toBe(3);
  });

  it("ignores items not on screen", () => {
    expect(moveTarget(items, [1, 2], 4, "up")).toBeNull();
  });
});

describe("applyMove (same rule as the server)", () => {
  it("removes and inserts at the target index", () => {
    expect(applyMove(items, 4, 1).map((i) => i.id)).toEqual([1, 4, 2, 3]);
    expect(applyMove(items, 1, 2).map((i) => i.id)).toEqual([2, 3, 1, 4]);
  });

  it("appends past the end and ignores unknown ids", () => {
    expect(applyMove(items, 1, 99).map((i) => i.id)).toEqual([2, 3, 4, 1]);
    expect(applyMove(items, 9, 0)).toBe(items);
  });

  it("round-trips with moveTarget for up then down", () => {
    const visible = items.map((i) => i.id);
    const up = applyMove(items, 3, moveTarget(items, visible, 3, "up")!);
    const back = applyMove(up, 3, moveTarget(up, up.map((i) => i.id), 3, "down")!);
    expect(back.map((i) => i.id)).toEqual([1, 2, 3, 4]);
  });
});

describe("range52wLabels", () => {
  const base = { high: 10, low: 5, from: "2025-10-09", firstDate: "2025-10-10", lastDate: "2026-10-08", tradingDays: 245 };

  it("calls a full window 52주", () => {
    expect(range52wLabels({ ...base, fullPeriod: true })).toEqual({ high: "52주 최고", low: "52주 최저" });
    expect(range52wLabels(null)).toEqual({ high: "52주 최고", low: "52주 최저" });
  });

  it("names the covered period when history is shorter than 52 weeks", () => {
    const l = range52wLabels({ ...base, firstDate: "2026-07-01", fullPeriod: false });
    expect(l.high).toBe("최고");
    expect(l.sub).toBe("2026.07.01~2026.10.08");
  });
});
