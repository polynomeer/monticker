"use client";

import { useMemo }       from "react";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import { useReducedMotion } from "@/stores/a11yStore";
import type { ChartTheme, CandleData, EventMarker, IndicatorKey, OrderLine, DrawingTool, Drawing, SignalMarker, SentimentMarker, TradeMarker, ChartInterval, ChartType } from "./types";
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
  signalMarkers?: SignalMarker[];
  sentimentMarkers?: SentimentMarker[];
  /** 거래(체결) 마커 — 매수▲·매도▼, 같은 봉의 여러 체결은 건수와 함께 묶인다 */
  trades?: TradeMarker[];
  /** candles의 봉 단위. 마커·드로잉을 Asia/Seoul 기준 봉 버킷에 맞출 때 쓴다(없으면 봉 간격으로 추정) */
  interval?: ChartInterval;
  /** 캔들·라인·영역·하이킨아시(기본 캔들) */
  chartType?: ChartType;
  activeDrawingTool?: DrawingTool | null;
  drawings?: Drawing[];
  onDrawingsChange?: (drawings: Drawing[]) => void;
  /** 자석 — 점을 가장 가까운 봉의 시·고·저·종 가격에 붙인다 */
  magnet?: boolean;
  /** 잠금 — 드로잉을 옮기거나 지울 수 없다 */
  drawingsLocked?: boolean;
  /** 한 번 쓰는 도구(확대)가 끝났을 때 */
  onDrawingToolDone?: () => void;
}

export default function StockChart({
  candles, events = [], height = 340, vwapData, onEventClick,
  enabledIndicators, orderLines, onCancelOrderLine, signalMarkers, sentimentMarkers,
  trades, interval, chartType, activeDrawingTool, drawings, onDrawingsChange,
  magnet, drawingsLocked, onDrawingToolDone,
}: Props) {
  const { chartTheme }    = useThemeStore();
  const ct                = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;
  const reduceMotion      = useReducedMotion();

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
    accent:    "#bd93f9", // dracula-purple — 라인·영역 차트 선
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
      signalMarkers={signalMarkers}
      sentimentMarkers={sentimentMarkers}
      trades={trades}
      interval={interval}
      chartType={chartType}
      activeDrawingTool={activeDrawingTool}
      drawings={drawings}
      onDrawingsChange={onDrawingsChange}
      magnet={magnet}
      drawingsLocked={drawingsLocked}
      onDrawingToolDone={onDrawingToolDone}
      reduceMotion={reduceMotion}
    />
  );
}
