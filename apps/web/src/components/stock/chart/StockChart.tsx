"use client";

/**
 * 차트 래퍼 컴포넌트.
 * 어댑터 구현체를 교체하려면 EChartsAdapter import만 바꾸면 됩니다.
 *
 * 예) import CanvasAdapter from "./CanvasAdapter";
 *     const ActiveAdapter = CanvasAdapter;
 */

import { useTheme }      from "next-themes";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import type { ChartTheme, CandleData, EventMarker } from "./types";
import EChartsAdapter from "./EChartsAdapter";  // ← 교체 지점

// 현재 활성 어댑터 — 라이브러리를 바꾸려면 이 줄만 수정
const ActiveAdapter = EChartsAdapter;

interface Props {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
}

export default function StockChart({ candles, events = [], height = 340 }: Props) {
  const { resolvedTheme }       = useTheme();
  const { chartTheme }          = useThemeStore();
  const ct                      = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;
  const isDark                  = resolvedTheme === "dark";

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
        className="border border-gray-200 dark:border-[#44475a] dark:bg-[#1e1f29]
                   rounded-lg flex items-center justify-center text-gray-400 dark:text-[#6272a4] text-sm"
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
    />
  );
}
