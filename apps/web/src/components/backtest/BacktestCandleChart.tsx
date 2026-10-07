"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import StockChart from "@/components/stock/chart/StockChart";
import type { EventMarker, IndicatorKey, TradeMarker } from "@/components/stock/chart/types";
import { kstDateToEpoch } from "@/components/stock/chart/tradeMarkers";
import { fetchCandles } from "@/hooks/useStockChart";

/** 백테스트 응답의 거래 한 건(진입·청산이 한 행) */
export interface BacktestTradeLike {
  entryDate: string;
  exitDate: string;
  entryPrice: number;
  exitPrice: number;
  /** 서버 TradeRecord.quantity — 없으면(구버전 응답) 마커를 만들지 않는다 */
  quantity?: number;
  exitReason: string;
}

const EXIT_LABEL: Record<string, string> = {
  SIGNAL: "신호", STOP_LOSS: "손절", TAKE_PROFIT: "익절", END: "기간 종료",
};

/** 백테스트 거래를 매수(진입)·매도(청산) 마커로 펼친다. 거래일은 KST 날짜라 그날 자정으로 둔다. */
export function backtestTradeMarkers(trades: BacktestTradeLike[]): TradeMarker[] {
  return trades.flatMap((t, i) => {
    if (t.quantity == null || !(t.quantity > 0)) return [];
    return [
      { id: `${i}-in`, time: kstDateToEpoch(t.entryDate), side: "BUY" as const, price: t.entryPrice, qty: t.quantity },
      { id: `${i}-out`, time: kstDateToEpoch(t.exitDate), side: "SELL" as const, price: t.exitPrice, qty: t.quantity, label: EXIT_LABEL[t.exitReason] ?? t.exitReason },
    ];
  }).filter((m) => Number.isFinite(m.time));
}

const NO_EVENTS: EventMarker[] = [];
const INDICATORS: IndicatorKey[] = ["MA5", "MA20"];

/** 백테스트 기간 일봉 + 매수·매도 마커 */
export default function BacktestCandleChart({
  stockId, fromDate, toDate, trades,
}: { stockId: number; fromDate: string; toDate: string; trades: BacktestTradeLike[] }) {
  const range = useMemo(() => ({
    from: new Date(kstDateToEpoch(fromDate) * 1000).toISOString(),
    to: new Date((kstDateToEpoch(toDate) + 86_400) * 1000 - 1000).toISOString(),
  }), [fromDate, toDate]);
  const { data: candles = [], isLoading, isError } = useQuery({
    queryKey: ["stocks", stockId, "candles", "1d", range.from, range.to],
    queryFn: () => fetchCandles(stockId, "1d", range),
    staleTime: 60_000,
  });
  const markers = useMemo(() => backtestTradeMarkers(trades), [trades]);

  if (isLoading) return <div className="h-[320px] animate-pulse rounded-lg bg-tm-inner" />;
  if (isError) return <p className="m-0 py-10 text-center text-13 text-tm-muted">캔들 데이터를 불러오지 못했습니다.</p>;
  return <StockChart candles={candles} events={NO_EVENTS} height={320} trades={markers} interval="1d" enabledIndicators={INDICATORS} />;
}
