import { describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import EChartsAdapter from "@/components/stock/chart/EChartsAdapter";
import { kstDateToEpoch } from "@/components/stock/chart/tradeMarkers";
import type { CandleData, TradeMarker } from "@/components/stock/chart/types";

const setOption = vi.fn();
vi.mock("echarts", () => ({
  init: () => ({
    setOption,
    on: vi.fn(),
    getZr: () => ({ on: vi.fn() }),
    isDisposed: () => false,
    dispose: vi.fn(),
    resize: vi.fn(),
    getWidth: () => 800,
    convertToPixel: () => 0,
    convertFromPixel: () => null,
  }),
}));

const theme = { bg: "#000", text: "#fff", grid: "#333", upColor: "#2563eb", downColor: "#f97316" };
const candles: CandleData[] = ["2026-10-05", "2026-10-06"].map((d, i) => ({
  time: kstDateToEpoch(d), open: 100 + i, high: 110 + i, low: 90 + i, close: 105 + i, volume: 1,
}));

type MarkPointItem = { coord: [number, number]; value: string; itemStyle?: { color?: string } };
type Opt = { series: Array<{ type: string; markPoint?: { data: MarkPointItem[] } }>; tooltip: { formatter: (p: unknown[]) => string } };

describe("EChartsAdapter trades", () => {
  it("trades를 캔들 시리즈 markPoint로 매핑하고 툴팁에 방향·수량·가격을 보인다", async () => {
    const trades: TradeMarker[] = [
      { time: candles[1].time + 3600, side: "BUY", qty: 10, price: 100 },
      { time: candles[1].time + 7200, side: "BUY", qty: 10, price: 102 },
      { time: candles[0].time + 3600, side: "SELL", qty: 4, price: 108 },
    ];
    render(<EChartsAdapter candles={candles} theme={theme} trades={trades} interval="1d" />);
    await waitFor(() => expect(setOption).toHaveBeenCalled());
    // 드로잉 다시 그리기(graphic만 담은 호출)는 건너뛰고 전체 옵션 호출을 본다
    const opt = setOption.mock.calls.map((c) => c[0] as Opt).find((o) => Array.isArray(o.series))!;
    const candle = opt.series.find((s) => s.type === "candlestick")!;
    const items = candle.markPoint!.data.filter((d) => /^[BS]\d*$/.test(String(d.value)));
    expect(items.map((d) => [d.value, d.coord[0], d.itemStyle?.color])).toEqual([
      ["S", 0, "#f97316"],
      ["B2", 1, "#2563eb"],
    ]);
    const tip = opt.tooltip.formatter([{ seriesType: "candlestick", value: null, dataIndex: 1 }]);
    expect(tip).toContain("매수 2건 · 20주 · 평균 101원");
  });
});
