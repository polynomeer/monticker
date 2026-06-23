"use client";

import { useEffect, useRef } from "react";
import { createChart, IChartApi, ISeriesApi, Time } from "lightweight-charts";
import { useTheme } from "next-themes";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

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
  PRICE_SPIKE:          "#0ecb81",
  PRICE_DROP:           "#f6465d",
  VOLUME_SURGE:         "#bd93f9",
  DISCLOSURE_PUBLISHED: "#a78bfa",
  default:              "#6272a4",
};

export default function StockChart({ candles, events = [], height = 300 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<IChartApi | null>(null);
  const seriesRef    = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const { resolvedTheme } = useTheme();
  const { chartTheme }    = useThemeStore();

  useEffect(() => {
    if (!containerRef.current) return;

    const isDark = resolvedTheme === "dark";
    const ct     = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;

    const chart = createChart(containerRef.current, {
      width: containerRef.current.clientWidth,
      height,
      layout: {
        background: { color: isDark ? "#1e1f29" : "#ffffff" },
        textColor:   isDark ? "#6272a4" : "#374151",
      },
      grid: {
        vertLines: { color: isDark ? "#44475a" : "#f0f0f0" },
        horzLines: { color: isDark ? "#44475a" : "#f0f0f0" },
      },
      timeScale: { timeVisible: true, secondsVisible: false },
    });

    // lightweight-charts v4: addCandlestickSeries (NOT addSeries)
    const series = chart.addCandlestickSeries({
      upColor:       ct.upColor,
      downColor:     ct.downColor,
      borderVisible: false,
      wickUpColor:   ct.wickUp,
      wickDownColor: ct.wickDown,
    });

    if (candles.length > 0) {
      series.setData(
        candles.map((c) => ({
          time:  c.time as Time,
          open:  c.open,
          high:  c.high,
          low:   c.low,
          close: c.close,
        }))
      );
    }

    if (events.length > 0) {
      series.setMarkers(
        events.map((e) => ({
          time:     e.time as Time,
          position: "aboveBar" as const,
          color:    EVENT_MARKER_COLOR[e.eventType] ?? EVENT_MARKER_COLOR.default,
          shape:    "arrowDown" as const,
          text:     e.title.slice(0, 10),
          size:     e.importanceScore > 70 ? 2 : 1,
        }))
      );
    }

    chartRef.current  = chart;
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
  }, [candles, events, height, resolvedTheme, chartTheme]);

  if (candles.length === 0) {
    return (
      <div
        className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg flex items-center justify-center text-gray-400 dark:text-[#6272a4] text-sm"
        style={{ height }}
      >
        차트 데이터 없음 (Worker 실행 후 candle 데이터가 쌓이면 표시됩니다)
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="w-full rounded-lg overflow-hidden border border-gray-200 dark:border-[#44475a]"
    />
  );
}
