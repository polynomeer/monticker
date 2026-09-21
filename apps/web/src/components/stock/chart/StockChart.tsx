"use client";

import { useMemo }       from "react";
import { useTheme }      from "next-themes";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import type { ChartTheme, CandleData, EventMarker, IndicatorKey, OrderLine, DrawingTool, Drawing } from "./types";
import EChartsAdapter from "./EChartsAdapter";

const ActiveAdapter = EChartsAdapter;

interface Props {
  candles: CandleData[];
  events?: EventMarker[];
  height?: number;
  vwapData?: Array<{ time: number; vwap: string }>;
  onEventClick?: (eventId: number) => void;
  enabledIndicators?: IndicatorKey[];
  orderLines?: OrderLine[];
  onCancelOrderLine?: (orderId: number) => void;
  activeDrawingTool?: DrawingTool | null;
  drawings?: Drawing[];
  onDrawingsChange?: (drawings: Drawing[]) => void;
}

export default function StockChart({
  candles, events = [], height = 340, vwapData, onEventClick,
  enabledIndicators, orderLines, onCancelOrderLine,
  activeDrawingTool, drawings, onDrawingsChange,
}: Props) {
  const { resolvedTheme } = useTheme();
  const { chartTheme }    = useThemeStore();
  const ct                = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;
  const isDark            = resolvedTheme === "dark";

  // 매 렌더 새 객체를 넘기면 EChartsAdapter가 (theme이 deps라) 차트를 통째로
  // dispose+재생성한다 — 드로잉 한 점 찍을 때마다 화면이 깜빡이는 원인이 되므로
  // 실제 색상 값이 바뀔 때만 참조가 바뀌도록 고정한다.
  const theme: ChartTheme = useMemo(() => ({
    bg:        isDark ? "#1e1f29" : "#ffffff",
    text:      isDark ? "#6272a4" : "#374151",
    grid:      isDark ? "#44475a" : "#e5e7eb",
    upColor:   ct.upColor,
    downColor: ct.downColor,
  }), [isDark, ct.upColor, ct.downColor]);

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
      onEventClick={onEventClick}
      enabledIndicators={enabledIndicators}
      orderLines={orderLines}
      onCancelOrderLine={onCancelOrderLine}
      activeDrawingTool={activeDrawingTool}
      drawings={drawings}
      onDrawingsChange={onDrawingsChange}
    />
  );
}
