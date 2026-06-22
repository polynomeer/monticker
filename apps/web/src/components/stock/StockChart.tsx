"use client";

import { useEffect, useRef } from "react";
import {
  createChart,
  IChartApi,
  ISeriesApi,
  CandlestickSeries,
  Time,
} from "lightweight-charts";

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

interface Props {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
}

const EVENT_MARKER_COLOR: Record<string, string> = {
  PRICE_SPIKE:  "#16a34a",
  PRICE_DROP:   "#dc2626",
  VOLUME_SURGE: "#2563eb",
  default:      "#6b7280",
};

export default function StockChart({ candles, events = [], height = 300 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    const isDark = typeof window !== "undefined" && document.documentElement.classList.contains("dark");
    const chart = createChart(containerRef.current, {
      width: containerRef.current.clientWidth,
      height,
      layout: {
        background: { color: isDark ? "#0b0e11" : "#ffffff" },
        textColor: isDark ? "#848e9c" : "#374151",
      },
      grid: {
        vertLines: { color: isDark ? "#2b3240" : "#f3f4f6" },
        horzLines: { color: isDark ? "#2b3240" : "#f3f4f6" },
      },
      timeScale: { timeVisible: true, secondsVisible: false },
    });

    const series = chart.addSeries(CandlestickSeries, {
      upColor: "#0ecb81",
      downColor: "#f6465d",
      borderVisible: false,
      wickUpColor: "#0ecb81",
      wickDownColor: "#f6465d",
    });

    if (candles.length > 0) {
      series.setData(
        candles.map((c) => ({
          time: c.time as Time,
          open: c.open,
          high: c.high,
          low: c.low,
          close: c.close,
        }))
      );
    }

    // Add event markers
    if (events.length > 0) {
      series.setMarkers(
        events.map((e) => ({
          time: e.time as Time,
          position: "aboveBar" as const,
          color: EVENT_MARKER_COLOR[e.eventType] ?? EVENT_MARKER_COLOR.default,
          shape: "arrowDown" as const,
          text: e.title.slice(0, 10),
          size: e.importanceScore > 70 ? 2 : 1,
        }))
      );
    }

    chartRef.current = chart;
    seriesRef.current = series;

    const handleResize = () => {
      if (containerRef.current) {
        chart.applyOptions({ width: containerRef.current.clientWidth });
      }
    };
    window.addEventListener("resize", handleResize);

    return () => {
      window.removeEventListener("resize", handleResize);
      chart.remove();
    };
  }, [candles, events, height]);

  if (candles.length === 0) {
    return (
      <div
        className="border border-gray-200 dark:border-[#2b3240] rounded-lg flex items-center justify-center text-gray-400 dark:text-[#848e9c] text-sm dark:bg-[#1e2329]"
        style={{ height }}
      >
        차트 데이터 없음 (Worker 실행 후 candle 데이터가 쌓이면 표시됩니다)
      </div>
    );
  }

  return <div ref={containerRef} className="w-full rounded-lg overflow-hidden border border-gray-200 dark:border-[#2b3240]" />;
}
