"use client";

import { useEffect, useState, useRef } from "react";
import type { CandleData, EventMarker } from "@/components/stock/chart/types";

export type { CandleData, EventMarker };

/** 두 캔들 배열이 실질적으로 동일한지 확인 (마지막 봉 close만 비교) */
function candlesEqual(a: CandleData[], b: CandleData[]): boolean {
  if (a.length !== b.length) return false;
  if (a.length === 0) return true;
  const la = a[a.length - 1];
  const lb = b[b.length - 1];
  return la.time === lb.time && la.close === lb.close && la.volume === lb.volume;
}

function eventsEqual(a: EventMarker[], b: EventMarker[]): boolean {
  if (a.length !== b.length) return false;
  if (a.length === 0) return true;
  return a[a.length - 1].time === b[b.length - 1].time;
}

export function useStockChart(stockId: number | null, interval: string = "1d") {
  const [candles, setCandles] = useState<CandleData[]>([]);
  const [events,  setEvents]  = useState<EventMarker[]>([]);
  const [loading, setLoading] = useState(true);

  // 최초 로딩 여부 — 이후 폴링은 loading=true 세팅 안 함 (깜빡임 방지)
  const initializedRef = useRef(false);

  useEffect(() => {
    if (!stockId) return;
    initializedRef.current = false;

    const fetchAll = async () => {
      if (!initializedRef.current) setLoading(true);

      try {
        const [candleRes, eventRes] = await Promise.all([
          fetch(`/api/stocks/${stockId}/candles?interval=${interval}`),
          fetch(`/api/stocks/${stockId}/events`),
        ]);

        if (candleRes.ok) {
          const data = await candleRes.json();
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
          const deduped = mapped.filter((c, i, arr) =>
            i === arr.length - 1 || c.time !== arr[i + 1].time
          );
          // 실질 변경 없으면 state 교체 안 함 → 자식 리렌더 방지
          setCandles(prev => candlesEqual(prev, deduped) ? prev : deduped);
        }

        if (eventRes.ok) {
          const data = await eventRes.json();
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
          setEvents(prev => eventsEqual(prev, mapped) ? prev : mapped);
        }
      } finally {
        initializedRef.current = true;
        setLoading(false);
      }
    };

    fetchAll();
    const id = setInterval(fetchAll, 10_000);
    return () => clearInterval(id);
  }, [stockId, interval]);

  return { candles, events, loading };
}
