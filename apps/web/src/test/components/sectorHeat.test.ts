import { describe, expect, it } from "vitest";
import { HEAT_SATURATION_PCT, heatBackground, heatmapSectors, type SectorPerformance } from "@/components/home/sectorHeat";

const row = (sector: string, stockCount: number, avgChangeRate: number | null): SectorPerformance => ({
  sector, stockCount, pricedCount: avgChangeRate == null ? 0 : stockCount, avgChangeRate,
  advancers: 0, decliners: 0, unchanged: 0, eventCount: 0,
});

describe("heatBackground", () => {
  it("has no color when there is no price data — not the same as 0%", () => {
    expect(heatBackground(null)).toBeNull();
    expect(heatBackground(undefined)).toBeNull();
    expect(heatBackground(Number.NaN)).toBeNull();
    expect(heatBackground(0)).not.toBeNull();
  });

  it("uses the theme up/down colors by sign", () => {
    expect(heatBackground(1)).toContain("--mt-up");
    expect(heatBackground(-1)).toContain("--mt-down");
  });

  it("saturates at the cap", () => {
    expect(heatBackground(HEAT_SATURATION_PCT)).toBe(heatBackground(HEAT_SATURATION_PCT * 10));
    expect(heatBackground(0.5)).not.toBe(heatBackground(2));
  });
});

describe("heatmapSectors", () => {
  it("puts sectors without prices last and orders by stock count", () => {
    const out = heatmapSectors([row("빈", 50, null), row("작은", 2, 1), row("큰", 10, -1)]);
    expect(out.map((r) => r.sector)).toEqual(["큰", "작은", "빈"]);
  });

  it("caps the number of tiles", () => {
    const many = Array.from({ length: 30 }, (_, i) => row(`s${i}`, i, 0));
    expect(heatmapSectors(many, 24)).toHaveLength(24);
  });
});
