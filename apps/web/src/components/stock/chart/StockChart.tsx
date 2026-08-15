"use client";

import { useTheme }      from "next-themes";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import type { ChartTheme, CandleData, EventMarker } from "./types";
import EChartsAdapter from "./EChartsAdapter";

const ActiveAdapter = EChartsAdapter;

interface Props {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
  vwapData?: Array<{ time: number; vwap: string }>;
}

export default function StockChart({ candles, events = [], height = 340, vwapData }: Props) {
  const { resolvedTheme } = useTheme();
  const { chartTheme }    = useThemeStore();
  const ct                = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;
  const isDark            = resolvedTheme === "dark";

  const theme: ChartTheme = {
    bg:        isDark ? "#1e1f29" : "#ffffff",
    text:      isDark ? "#6272a4" : "#374151",
    grid:      isDark ? "#44475a" : "#e5e7eb",
    upColor:   ct.upColor,
    downColor: ct.downColor,
  };

  if (candles.length === 0) {
    return (
      <div
        className="border border-gray-200 dark:border-dracula-line dark:bg-[#1e1f29]
                   rounded-lg flex items-center justify-center text-gray-400 dark:text-dracula-comment text-sm"
        style={{ height }}
      >
        차트 데이터 없음
      </div>
    );
  }

  return (
    <ActiveAdapter
      candles={candles}
      events={events}
      height={height}
      theme={theme}
      vwapData={vwapData}
    />
  );
}
