"use client";

import { useEffect, useState } from "react";

interface CandleData {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
}

interface EventMarker {
  time: number;
  eventType: string;
  title: string;
  importanceScore: number;
}

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
          const mapped = data.map((c: { time: number; open: string; high: string; low: string; close: string }) => ({
            time: c.time,
            open: parseFloat(c.open),
            high: parseFloat(c.high),
            low: parseFloat(c.low),
            close: parseFloat(c.close),
          }));
          // Lightweight Charts requires strictly ascending time order
          mapped.sort((a: CandleData, b: CandleData) => a.time - b.time);
          // Remove duplicate timestamps (keep last)
          const deduped = mapped.filter((c: CandleData, i: number, arr: CandleData[]) =>
            i === arr.length - 1 || c.time !== arr[i + 1].time
          );
          setCandles(deduped);
        }

        if (eventRes.ok) {
          const data = await eventRes.json();
          const mappedEvents = data.map((e: { eventTime: string; eventType: string; title: string; importanceScore: number }) => ({
            time: Math.floor(new Date(e.eventTime).getTime() / 1000),
            eventType: e.eventType,
            title: e.title,
            importanceScore: e.importanceScore,
          }));
          mappedEvents.sort((a: EventMarker, b: EventMarker) => a.time - b.time);
          setEvents(mappedEvents);
        }
      } finally {
        setLoading(false);
      }
    };

    fetchAll();
    const interval_ = setInterval(fetchAll, 10000);
    return () => clearInterval(interval_);
  }, [stockId, interval]);

  return { candles, events, loading };
}
