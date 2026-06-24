"use client";

import { useQuery } from "@tanstack/react-query";
import type { CandleData, EventMarker } from "@/components/stock/chart/types";

export type { CandleData, EventMarker };

// ── Query Key 팩토리 ─────────────────────────────────────────
// EventTimeline, useStockChart 등 여러 컴포넌트가 같은 키를 사용하면
// TanStack Query가 하나의 요청으로 자동 병합 (Single Flight)
export const stockKeys = {
  candles: (stockId: number, interval: string) =>
    ["stocks", stockId, "candles", interval] as const,
  events: (stockId: number) =>
    ["stocks", stockId, "events"] as const,
  news: (stockId: number) =>
    ["stocks", stockId, "news"] as const,
  summary: (stockId: number) =>
    ["stocks", stockId, "summary"] as const,
  orderbook: (stockId: number) =>
    ["stocks", stockId, "orderbook"] as const,
};

// ── Fetchers ─────────────────────────────────────────────────
async function fetchCandles(stockId: number, interval: string): Promise<CandleData[]> {
  const res = await fetch(`/api/stocks/${stockId}/candles?interval=${interval}`);
  if (!res.ok) return [];
  const data = await res.json();
  const mapped: CandleData[] = data.map((c: {
    time: number; open: string; high: string;
    low: string; close: string; volume?: number;
  }) => ({
    time:   c.time,
    open:   parseFloat(c.open),
    high:   parseFloat(c.high),
    low:    parseFloat(c.low),
    close:  parseFloat(c.close),
    volume: c.volume ?? 0,
  }));
  mapped.sort((a, b) => a.time - b.time);
  return mapped.filter((c, i, arr) =>
    i === arr.length - 1 || c.time !== arr[i + 1].time
  );
}

async function fetchEvents(stockId: number): Promise<EventMarker[]> {
  const res = await fetch(`/api/stocks/${stockId}/events`);
  if (!res.ok) return [];
  const data = await res.json();
  const mapped: EventMarker[] = data.map((e: {
    eventTime: string; eventType: string;
    title: string; importanceScore: number;
  }) => ({
    time:            Math.floor(new Date(e.eventTime).getTime() / 1000),
    eventType:       e.eventType,
    title:           e.title,
    importanceScore: e.importanceScore,
  }));
  mapped.sort((a, b) => a.time - b.time);
  return mapped;
}

// ── Hook ─────────────────────────────────────────────────────
export function useStockChart(stockId: number | null, interval: string = "1d") {
  const { data: candles = [], isLoading: loadingCandles } = useQuery({
    queryKey:       stockId ? stockKeys.candles(stockId, interval) : ["disabled"],
    queryFn:        () => fetchCandles(stockId!, interval),
    enabled:        !!stockId,
    refetchInterval: 10_000,   // 10초마다 백그라운드 갱신
    staleTime:      10_000,
  });

  const { data: events = [], isLoading: loadingEvents } = useQuery({
    queryKey:        stockId ? stockKeys.events(stockId) : ["disabled"],
    queryFn:         () => fetchEvents(stockId!),
    enabled:         !!stockId,
    refetchInterval: 10_000,
    staleTime:       10_000,
  });

  return {
    candles,
    events,
    loading: loadingCandles || loadingEvents,
  };
}

// fetchEvents를 외부에서도 사용 가능하도록 export
export { fetchCandles, fetchEvents };
