"use client";

import { useMemo }       from "react";
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
  const { chartTheme }    = useThemeStore();
  const ct                = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;

  // 매 렌더 새 객체를 넘기면 EChartsAdapter가 (theme이 deps라) 차트를 통째로
  // dispose+재생성한다 — 드로잉 한 점 찍을 때마다 화면이 깜빡이는 원인이 되므로
  // 실제 색상 값이 바뀔 때만 참조가 바뀌도록 고정한다.
  // 터미널 디자인은 다크 전용 — 패널 배경(tm-panel)·보조 글자(tm-muted)·구분선(tm-line)에 맞춘다.
  const theme: ChartTheme = useMemo(() => ({
    bg:        "#282a36",
    text:      "#a4abcf",
    grid:      "#34364a",
    upColor:   ct.upColor,
    downColor: ct.downColor,
  }), [ct.upColor, ct.downColor]);

  if (candles.length === 0) {
    return (
      <div
        className="flex items-center justify-center rounded-lg bg-tm-inner text-13 text-tm-muted"
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
