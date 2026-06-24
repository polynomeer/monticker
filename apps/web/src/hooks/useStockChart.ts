"use client";

import { useEffect, useState } from "react";
import type { CandleData, EventMarker } from "@/components/stock/chart/types";

export type { CandleData, EventMarker };

export function useStockChart(stockId: number | null, interval: string = "1d") {
  const [candles, setCandles] = useState<CandleData[]>([]);
  const [events, setEvents] = useState<EventMarker[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!stockId) return;

    const fetchAll = async () => {
      setLoading(true);
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
          // Lightweight / ECharts 공통 요구사항: 오름차순 + 중복 time 제거
          mapped.sort((a, b) => a.time - b.time);
          const deduped = mapped.filter((c, i, arr) =>
            i === arr.length - 1 || c.time !== arr[i + 1].time
          );
          setCandles(deduped);
        }

        if (eventRes.ok) {
          const data = await eventRes.json();
          const mappedEvents: EventMarker[] = data.map((e: {
            eventTime: string; eventType: string;
            title: string; importanceScore: number;
          }) => ({
            time:            Math.floor(new Date(e.eventTime).getTime() / 1000),
            eventType:       e.eventType,
            title:           e.title,
            importanceScore: e.importanceScore,
          }));
          mappedEvents.sort((a, b) => a.time - b.time);
          setEvents(mappedEvents);
        }
      } finally {
        setLoading(false);
      }
    };

    fetchAll();
    const id = setInterval(fetchAll, 10_000);
    return () => clearInterval(id);
  }, [stockId, interval]);

  return { candles, events, loading };
}
