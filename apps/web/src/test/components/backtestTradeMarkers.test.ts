import { describe, expect, it } from "vitest";
import { backtestTradeMarkers } from "@/components/backtest/BacktestCandleChart";
import { aggregateTradeMarkers, kstDateToEpoch } from "@/components/stock/chart/tradeMarkers";

describe("backtestTradeMarkers", () => {
  it("거래 한 건을 진입 매수·청산 매도 마커로 펼친다(KST 날짜)", () => {
    const m = backtestTradeMarkers([
      { entryDate: "2026-06-01", exitDate: "2026-06-05", entryPrice: 70000, exitPrice: 77000, quantity: 10, exitReason: "TAKE_PROFIT" },
    ]);
    expect(m).toEqual([
      { id: "0-in", time: kstDateToEpoch("2026-06-01"), side: "BUY", price: 70000, qty: 10 },
      { id: "0-out", time: kstDateToEpoch("2026-06-05"), side: "SELL", price: 77000, qty: 10, label: "익절" },
    ]);
  });

  it("수량이 없는 응답이면 마커를 지어내지 않는다", () => {
    expect(backtestTradeMarkers([
      { entryDate: "2026-06-01", exitDate: "2026-06-05", entryPrice: 1, exitPrice: 2, exitReason: "END" },
    ])).toEqual([]);
  });

  it("같은 날 청산 후 재진입은 매수·매도 마커 각각 하나로 같은 일봉에 붙는다", () => {
    const candles = ["2026-06-01", "2026-06-02", "2026-06-03"].map((d) => ({ time: kstDateToEpoch(d) }));
    const m = backtestTradeMarkers([
      { entryDate: "2026-06-01", exitDate: "2026-06-02", entryPrice: 1, exitPrice: 2, quantity: 5, exitReason: "SIGNAL" },
      { entryDate: "2026-06-02", exitDate: "2026-06-03", entryPrice: 2, exitPrice: 3, quantity: 5, exitReason: "END" },
    ]);
    const g = aggregateTradeMarkers(candles, m, "1d");
    expect(g.map((x) => [x.index, x.side])).toEqual([[0, "BUY"], [1, "BUY"], [1, "SELL"], [2, "SELL"]]);
  });
});
