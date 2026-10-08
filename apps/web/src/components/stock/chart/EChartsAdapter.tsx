"use client";

/**
 * Apache ECharts 기반 프로 차트 어댑터.
 * TradingView 스타일: MA5/MA20, 현재가 라인, OHLCV 툴팁, 거래량 패널, 줌/패닝.
 * 고급 모드: 자유 지표(MA60/볼린저밴드), 실전투자 미체결 주문선, 추세선/수평선 드로잉.
 */

import { useEffect, useRef, useCallback, useState } from "react";
import type { ChartAdapterProps, CandleData, IndicatorKey, Drawing, DrawingPoint, SignalMarker, SentimentMarker, TradeMarker, ChartInterval } from "./types";
import { aggregateTradeMarkers, describeTradeGroup, tradeMarkPoints, tradeTimesKst } from "./tradeMarkers";
import { candleIndexAt, fmtCandleLabel, fractionalIndexAt, inferInterval, isIntraday, timeAtIndex } from "./chartTime";
import { decimate, estimateLabelWidth, formatMeasure, heikinAshi, isDrawingMeaningful, magnetSnap, measure, placeLabel, shiftPoints, zoomWindow, type IndexedPoint } from "./drawingGeometry";
import { DRAWING_LIMITS } from "./drawingStorage";

let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

// ── 이동평균 / 볼린저밴드 계산 ─────────────────────────────────
function calcMA(data: CandleData[], period: number): (number | null)[] {
  return data.map((_, i) => {
    if (i < period - 1) return null;
    const slice = data.slice(i - period + 1, i + 1);
    return slice.reduce((s, c) => s + c.close, 0) / period;
  });
}

function calcBollingerBands(data: CandleData[], period = 20) {
  const upper: (number | null)[] = [];
  const mid: (number | null)[] = [];
  const lower: (number | null)[] = [];
  data.forEach((_, i) => {
    if (i < period - 1) { upper.push(null); mid.push(null); lower.push(null); return; }
    const slice = data.slice(i - period + 1, i + 1).map(c => c.close);
    const mean = slice.reduce((s, v) => s + v, 0) / period;
    const variance = slice.reduce((s, v) => s + (v - mean) * (v - mean), 0) / period;
    const stdDev = Math.sqrt(variance);
    upper.push(mean + 2 * stdDev);
    mid.push(mean);
    lower.push(mean - 2 * stdDev);
  });
  return { upper, mid, lower };
}

// ── 이벤트 타입 색상 ─────────────────────────────────────────
// 터미널 시안의 이벤트 색·글자(components/stock/parts.tsx EVENT_META와 같은 값)
const EVENT_COLORS: Record<string, string> = {
  PRICE_SPIKE:          "#50fa7b",
  PRICE_DROP:           "#ff79c6",
  VOLUME_SURGE:         "#bd93f9",
  NEWS_PUBLISHED:       "#8be9fd",
  DISCLOSURE_PUBLISHED: "#ffb86c",
  default:              "#c3c8e2",
};
const EVENT_LETTERS: Record<string, string> = {
  PRICE_SPIKE: "P", PRICE_DROP: "P", VOLUME_SURGE: "V", NEWS_PUBLISHED: "N", DISCLOSURE_PUBLISHED: "D", SECTOR_MOVE: "S",
};

// ── 숫자 포맷 ────────────────────────────────────────────────
function fmtPrice(v: number): string {
  if (v >= 100) return v.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
  return v.toLocaleString("ko-KR", { maximumFractionDigits: 2 });
}
function fmtVol(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(2)}M`;
  if (v >= 1_000)     return `${(v / 1_000).toFixed(0)}K`;
  return String(v);
}
// 드로잉 색 — 터미널 팔레트(Dracula)와 같은 값
const DRAW_COLORS = {
  trend: "#ffb86c",
  pen:   "#f1fa8c",
  zoom:  "#bd93f9",
} as const;

/** 메인 시리즈 이름 — 툴팁이 이 시리즈의 dataIndex로 봉을 찾는다 */
const MAIN_SERIES = "OHLC";
/** 드로잉 그래픽 그룹 id — 매번 통째로 교체해 지운 드로잉이 남지 않게 한다 */
const DRAWINGS_GROUP = "mt-drawings";
/** 펜 획에 점을 더하는 최소 픽셀 거리 */
const PEN_MIN_PX = 3;
/** 메인 그리드 여백(아래 grid 옵션과 같은 값) */
const GRID_LEFT = 48, GRID_RIGHT = 82, GRID_TOP = 32;

// 기본값을 모듈 상수로 — 매 렌더 새 []면 buildOption deps가 바뀌어 차트를 통째로 다시 만든다.
const NO_SIGNALS: SignalMarker[] = [];
const NO_SENTIMENT: SentimentMarker[] = [];
const NO_TRADES: TradeMarker[] = [];
const NO_DRAWINGS: Drawing[] = [];

/** 차트 위 한 점 — 봉 인덱스(소수 가능)와 저장용 데이터 좌표 */
interface ChartPoint extends IndexedPoint {
  time: number;
}

export default function EChartsAdapter({
  candles,
  events = [],
  height = 420,
  theme,
  vwapData,
  onEventClick,
  enabledIndicators,
  orderLines = [],
  onCancelOrderLine,
  signalMarkers = NO_SIGNALS,
  sentimentMarkers = NO_SENTIMENT,
  trades = NO_TRADES,
  interval: intervalProp,
  chartType = "candle",
  magnet = false,
  drawingsLocked = false,
  onDrawingToolDone,
  activeDrawingTool = null,
  drawings = NO_DRAWINGS,
  onDrawingsChange,
  reduceMotion = false,
}: ChartAdapterProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<import("echarts").ECharts | null>(null);
  /** 두 점 도구(추세선·측정·확대)의 첫 점 */
  const pendingPointRef = useRef<ChartPoint | null>(null);
  /** 첫 점을 찍은 뒤 마우스 위치 — 미리보기용 */
  const hoverRef = useRef<ChartPoint | null>(null);
  /** 그리는 중인 펜 획 */
  const penRef = useRef<{ points: DrawingPoint[]; lastPx: [number, number] } | null>(null);
  /** 완성된 측정 결과(저장하지 않음 — 도구를 바꾸면 사라진다) */
  const measureRef = useRef<{ a: ChartPoint; b: ChartPoint } | null>(null);
  /** 드로잉을 끌어서 옮긴 직후의 클릭을 무시하기 위한 표시 */
  const draggedAtRef = useRef(0);
  /** 마지막 줌 상태 — 실시간 갱신으로 차트를 다시 만들 때 사용자가 보던 구간을 유지한다 */
  const zoomRef = useRef<{ firstTime: number; start: number; end: number } | null>(null);
  const [textDraft, setTextDraft] = useState<{ left: number; top: number; point: DrawingPoint } | null>(null);

  const interval: ChartInterval = intervalProp ?? inferInterval(candles.map(c => c.time));

  // 콜백/드로잉 목록은 매 렌더 새 값이 올 수 있어 이벤트 핸들러 안에서는 항상
  // 최신 값을 ref로 읽는다 — 차트 인스턴스를 매번 재생성하지 않기 위함.
  const latest = { activeDrawingTool, drawings, onDrawingsChange, onEventClick, onCancelOrderLine, onDrawingToolDone, candles, interval, magnet, drawingsLocked };
  const latestRef = useRef(latest);
  latestRef.current = latest;

  const indicators: Set<IndicatorKey> = new Set(enabledIndicators ?? ["MA5", "MA20"]);

  const buildOption = useCallback(
    () => {
      if (!candles.length) return null;

      const times  = candles.map(c => c.time);
      const dates  = candles.map(c => fmtCandleLabel(c.time, interval));
      const shown  = chartType === "heikin-ashi" ? heikinAshi(candles) : candles;
      const ohlcv  = shown.map(c => [c.open, c.close, c.low, c.high]);
      const vols   = candles.map(c => c.volume ?? 0);
      const last   = candles[candles.length - 1];
      const prev   = candles[candles.length - 2];
      const chg    = prev ? ((last.close - prev.close) / prev.close) * 100 : 0;
      const isUp   = last.close >= last.open;
      const accent = theme.accent ?? theme.text;
      const zk     = zoomRef.current;
      const keptZoom = zk && zk.firstTime === candles[0].time ? zk : null;

      // 시각 t가 속한 봉(Asia/Seoul 버킷). 차트 구간 밖이면 -1 — 표시하지 않는다.
      // x 좌표는 category 축 인덱스로 준다 — 날짜 라벨로 주면 분봉에서 그날 첫 봉에 붙는다.
      const candleIndexOf = (t: number) => candleIndexAt(times, t, interval);

      // 이벤트 마커 (markPoint)
      const markData = events
        .map(e => {
          const idx = candleIndexOf(e.time);
          if (idx < 0) return null;
          const color = EVENT_COLORS[e.eventType] ?? EVENT_COLORS.default;
          return {
            name:  e.title,
            coord: [idx, candles[idx].high],
            value: EVENT_LETTERS[e.eventType] ?? "E",
            // 시안의 원형 마커 — 페이지 배경 원 + 이벤트 색 테두리 + 글자
            itemStyle: { color: "#1b1c24", borderColor: color, borderWidth: 2 },
            label: { color },
            symbolSize: e.importanceScore > 70 ? 22 : 18,
            // markPoint 데이터 항목에 자유 필드를 얹어두면 클릭 이벤트의 params.data로
            // 그대로 돌아온다 — 이벤트 타임라인으로 점프할 때 이 id로 정확히 매칭한다.
            eventId: e.id,
          };
        })
        .filter(Boolean);

      // 퀀트 시그널 — 매수는 봉 아래 ▲, 매도는 봉 위 ▼
      for (const s of signalMarkers) {
        const idx = candleIndexOf(s.time);
        if (idx < 0) continue;
        const buy = s.direction === "BUY";
        markData.push({
          name: `${s.label} ${buy ? "매수" : "매도"} 신호`,
          coord: [idx, buy ? candles[idx].low : candles[idx].high],
          value: buy ? "▲" : "▼",
          symbol: "pin",
          symbolRotate: buy ? 180 : 0,
          itemStyle: { color: buy ? theme.upColor : theme.downColor },
          label: { color: "#1b1c24", fontSize: 9 },
          symbolSize: 20,
          eventId: undefined,
        } as never);
      }
      // 감성 — 점수 부호로 색, 크기는 절댓값
      for (const s of sentimentMarkers) {
        const idx = candleIndexOf(s.time);
        if (idx < 0) continue;
        const color = s.score > 0.05 ? "#50fa7b" : s.score < -0.05 ? "#ff5555" : "#6272a4";
        markData.push({
          name: `감성 ${s.score >= 0 ? "+" : ""}${s.score.toFixed(2)} · ${s.title}`,
          coord: [idx, candles[idx].high],
          value: s.score >= 0 ? "+" : "−",
          itemStyle: { color: "#1b1c24", borderColor: color, borderWidth: 2 },
          label: { color },
          symbolSize: 12 + Math.round(Math.min(1, Math.abs(s.score)) * 8),
          eventId: undefined,
        } as never);
      }

      // 거래 마커 — 같은 봉·같은 방향 체결은 건수와 함께 하나로 묶는다
      const tradeGroups = aggregateTradeMarkers(candles, trades, interval);
      markData.push(...(tradeMarkPoints(tradeGroups, candles, theme) as never[]));
      const tradeGroupsAt = new Map<number, typeof tradeGroups>();
      for (const g of tradeGroups) tradeGroupsAt.set(g.index, [...(tradeGroupsAt.get(g.index) ?? []), g]);

      // 현재가 라인 + 실전투자 미체결 주문선을 같은 markLine에 합친다(시리즈당
      // markLine은 하나뿐이라 data 배열에 함께 넣어야 한다).
      const priceLine = { yAxis: last.close, orderId: undefined as number | undefined };
      const orderLineData = orderLines.map(o => ({
        yAxis: o.price,
        orderId: o.id,
        lineStyle: {
          color: o.side === "BUY" ? theme.upColor : theme.downColor,
          type: "solid" as const,
          width: 1.5,
        },
        label: {
          show: true,
          position: "insideEndTop" as const,
          formatter: () => `${o.label} ${fmtPrice(o.price)}`,
          color: o.side === "BUY" ? theme.upColor : theme.downColor,
          fontSize: 10,
          fontWeight: "bold" as const,
        },
      }));

      const CHART_H  = height - 100; // 캔들 패널 높이
      const VOL_TOP  = CHART_H + 8;

      const ma5  = indicators.has("MA5")  ? calcMA(candles, 5)  : null;
      const ma20 = indicators.has("MA20") ? calcMA(candles, 20) : null;
      const ma60 = indicators.has("MA60") ? calcMA(candles, 60) : null;
      const boll = indicators.has("BOLL") ? calcBollingerBands(candles, 20) : null;

      const legendData = [
        ...(ma5 ? ["MA5"] : []),
        ...(ma20 ? ["MA20"] : []),
        ...(ma60 ? ["MA60"] : []),
        ...(boll ? ["BOLL"] : []),
        ...(vwapData?.length ? ["VWAP"] : []),
      ];

      return {
        backgroundColor: theme.bg,
        // 시리즈 애니메이션은 항상 끈다(실시간 갱신마다 봉이 움직이지 않게). 움직임 줄이기면 툴팁·십자선 전환까지 끈다.
        animation: false,
        stateAnimation: reduceMotion ? { duration: 0 } : undefined,

        // ── 범례 ────────────────────────────────────────────
        legend: {
          top: 6, left: 8,
          icon: "line",
          itemWidth: 16, itemHeight: 2,
          textStyle: { color: theme.text, fontSize: 11 },
          data: legendData,
        },

        // ── 툴팁 ────────────────────────────────────────────
        tooltip: {
          trigger: "axis",
          transitionDuration: reduceMotion ? 0 : 0.4,
          axisPointer: { type: "cross", crossStyle: { color: theme.text }, animation: !reduceMotion },
          backgroundColor: theme.bg === "#ffffff" ? "#f8f8f2" : "#282a36",
          borderColor: theme.grid,
          padding: [8, 12],
          textStyle: { color: theme.text, fontSize: 12 },
          formatter(params: unknown[]) {
            const ps = params as { seriesType: string; seriesName?: string; value: unknown; dataIndex: number }[];
            const cdl = ps.find(p => p.seriesName === MAIN_SERIES || p.seriesType === "candlestick");
            if (!cdl) return "";
            const i   = cdl.dataIndex;
            const c   = candles[i];
            if (!c) return "";
            const col = c.close >= c.open ? theme.upColor : theme.downColor;
            const pct = i > 0
              ? ((c.close - candles[i - 1].close) / candles[i - 1].close * 100)
              : 0;
            const sign = pct >= 0 ? "+" : "";
            return [
              `<b style="color:${theme.text}">${fmtCandleLabel(c.time, interval)}</b>`,
              ...(chartType === "heikin-ashi" ? [`<small style="color:${theme.text}">하이킨아시 표시 · 아래는 실제 시세</small>`] : []),
              `<span style="color:${theme.text}">시가 </span><b style="color:${col}">${fmtPrice(c.open)}</b>`,
              `<span style="color:${theme.text}">고가 </span><b style="color:${theme.upColor}">${fmtPrice(c.high)}</b>`,
              `<span style="color:${theme.text}">저가 </span><b style="color:${theme.downColor}">${fmtPrice(c.low)}</b>`,
              `<span style="color:${theme.text}">종가 </span><b style="color:${col}">${fmtPrice(c.close)} <small>(${sign}${pct.toFixed(2)}%)</small></b>`,
              `<span style="color:${theme.text}">거래량 </span><b style="color:${theme.text}">${fmtVol(c.volume ?? 0)}</b>`,
              ...(tradeGroupsAt.get(i) ?? []).map(g =>
                `<b style="color:${g.side === "BUY" ? theme.upColor : theme.downColor}">${describeTradeGroup(g)}</b>` +
                `<br/><small style="color:${theme.text}">${tradeTimesKst(g)}</small>`),
            ].join("<br/>");
          },
        },

        // ── 그리드 ──────────────────────────────────────────
        grid: [
          { left: GRID_LEFT, right: GRID_RIGHT, top: GRID_TOP, bottom: height - CHART_H + 4 },
          { left: 48, right: 82, top: VOL_TOP, bottom: 52 },
        ],

        // ── X축 ─────────────────────────────────────────────
        xAxis: [
          {
            type: "category", data: dates, gridIndex: 0,
            boundaryGap: false,
            axisLine:   { lineStyle: { color: theme.grid } },
            axisTick:   { lineStyle: { color: theme.grid } },
            axisLabel:  { show: false },
            splitLine:  { show: true, lineStyle: { color: theme.grid, type: "dashed", opacity: 0.5 } },
          },
          {
            type: "category", data: dates, gridIndex: 1,
            boundaryGap: false,
            axisLine:   { lineStyle: { color: theme.grid } },
            axisTick:   { lineStyle: { color: theme.grid } },
            axisLabel:  { color: theme.text, fontSize: 10, margin: 8 },
            splitLine:  { show: false },
          },
        ],

        // ── Y축 ─────────────────────────────────────────────
        yAxis: [
          {
            scale: true, gridIndex: 0,
            position: "right",
            axisLine:  { show: true, lineStyle: { color: theme.grid } },
            axisTick:  { show: true, lineStyle: { color: theme.grid } },
            splitLine: { lineStyle: { color: theme.grid, type: "dashed", opacity: 0.5 } },
            axisLabel: {
              color: theme.text, fontSize: 11,
              formatter: (v: number) => fmtPrice(v),
              inside: false,
              margin: 6,
            },
            // 현재가 라인
            axisPointer: { label: { formatter: ({ value }: { value: number }) => fmtPrice(value) } },
          },
          {
            scale: true, gridIndex: 1,
            position: "right",
            splitNumber: 2,
            axisLine:  { show: false },
            axisTick:  { show: false },
            splitLine: { show: false },
            axisLabel: {
              color: theme.text, fontSize: 10,
              formatter: (v: number) => fmtVol(v),
            },
          },
        ],

        // ── 줌 ──────────────────────────────────────────────
        dataZoom: [
          {
            id: "mt-inside",
            type: "inside",
            xAxisIndex: [0, 1],
            // 같은 구간(첫 봉이 같은)을 다시 그릴 때는 사용자가 보던 줌 구간을 유지한다
            start: keptZoom ? keptZoom.start : Math.max(0, 100 - (30 / candles.length) * 100),
            end: keptZoom ? keptZoom.end : 100,
            zoomOnMouseWheel: true,
            // 펜으로 그리는 동안에는 드래그가 팬이 되지 않게 한다
            moveOnMouseMove: latestRef.current.activeDrawingTool !== "PEN",
          },
          {
            type: "slider",
            xAxisIndex: [0, 1],
            bottom: 4, height: 22,
            borderColor: theme.grid,
            backgroundColor: theme.bg,
            fillerColor: `${theme.text}22`,
            handleStyle: { color: theme.text },
            moveHandleStyle: { color: theme.text },
            textStyle: { color: theme.text, fontSize: 10 },
            labelFormatter: (_: unknown, v: string) => (isIntraday(interval) ? v : v.slice(5)),
            emphasis: {
              handleStyle: { color: theme.upColor },
              moveHandleStyle: { color: theme.upColor },
            },
          },
        ],

        // ── 시리즈 ──────────────────────────────────────────
        series: [
          // 메인 시리즈 — 캔들·하이킨아시는 candlestick, 라인·영역은 종가 line
          {
            name: MAIN_SERIES,
            xAxisIndex: 0, yAxisIndex: 0,
            ...(chartType === "line" || chartType === "area"
              ? {
                  type: "line",
                  data: candles.map(c => c.close),
                  symbol: "none",
                  lineStyle: { color: accent, width: 1.5 },
                  itemStyle: { color: accent },
                  ...(chartType === "area" ? { areaStyle: { color: `${accent}33` } } : {}),
                }
              : {
                  type: "candlestick",
                  data: ohlcv,
                  itemStyle: {
                    color:        theme.upColor,
                    color0:       theme.downColor,
                    borderColor:  theme.upColor,
                    borderColor0: theme.downColor,
                    borderWidth: 1,
                  },
                }),
            markPoint: {
              symbol: "circle",
              symbolOffset: [0, -16],
              data: markData as never[],
              label: { show: true, fontSize: 10, fontWeight: "bold", fontFamily: "JetBrains Mono, monospace" },
            },
            markLine: {
              symbol: ["none", "none"],
              lineStyle: {
                color: isUp ? theme.upColor : theme.downColor,
                type: "dashed",
                width: 1,
                opacity: 0.8,
              },
              label: {
                show: true,
                position: "end",
                formatter: () => `${fmtPrice(last.close)}  ${chg >= 0 ? "+" : ""}${chg.toFixed(2)}%`,
                color: "#fff",
                backgroundColor: isUp ? theme.upColor : theme.downColor,
                padding: [2, 6],
                borderRadius: 3,
                fontSize: 11,
              },
              // 현재가 라인(silent) + 주문선(클릭 가능, 취소용)을 한 markLine에 합친다.
              data: [priceLine, ...orderLineData] as never[],
            },
          },

          ...(ma5 ? [{
            name: "MA5", type: "line", xAxisIndex: 0, yAxisIndex: 0,
            data: ma5, smooth: true, symbol: "none",
            lineStyle: { color: "#f1fa8c", width: 1.5, opacity: 0.9 },
            tooltip: { show: false },
          }] : []),

          ...(ma20 ? [{
            name: "MA20", type: "line", xAxisIndex: 0, yAxisIndex: 0,
            data: ma20, smooth: true, symbol: "none",
            lineStyle: { color: "#8be9fd", width: 1.5, opacity: 0.9 },
            tooltip: { show: false },
          }] : []),

          ...(ma60 ? [{
            name: "MA60", type: "line", xAxisIndex: 0, yAxisIndex: 0,
            data: ma60, smooth: true, symbol: "none",
            lineStyle: { color: "#ffb86c", width: 1.5, opacity: 0.9 },
            tooltip: { show: false },
          }] : []),

          ...(boll ? [
            {
              name: "BOLL", type: "line", xAxisIndex: 0, yAxisIndex: 0,
              data: boll.upper, smooth: true, symbol: "none",
              lineStyle: { color: "#bd93f9", width: 1, opacity: 0.6, type: "dashed" as const },
              tooltip: { show: false },
            },
            {
              name: "BOLL", type: "line", xAxisIndex: 0, yAxisIndex: 0,
              data: boll.mid, smooth: true, symbol: "none",
              lineStyle: { color: "#bd93f9", width: 1, opacity: 0.8 },
              tooltip: { show: false }, legendHoverLink: false,
            },
            {
              name: "BOLL", type: "line", xAxisIndex: 0, yAxisIndex: 0,
              data: boll.lower, smooth: true, symbol: "none",
              lineStyle: { color: "#bd93f9", width: 1, opacity: 0.6, type: "dashed" as const },
              tooltip: { show: false }, legendHoverLink: false,
            },
          ] : []),

          // VWAP
          ...(vwapData && vwapData.length > 0 ? [{
            name: "VWAP",
            type: "line",
            xAxisIndex: 0, yAxisIndex: 0,
            data: (() => {
              const map = new Map(vwapData.map((p: { time: number; vwap: string }) => [p.time, parseFloat(p.vwap)]));
              return candles.map(c => map.get(c.time) ?? null);
            })(),
            smooth: false, symbol: "none",
            lineStyle: { color: "#ff79c6", width: 1.5, type: "dashed" as const },
            tooltip: { show: false },
          }] : []),
          // 거래량 바
          {
            name: "Volume",
            type: "bar",
            xAxisIndex: 1, yAxisIndex: 1,
            data: vols.map((v, i) => ({
              value: v,
              itemStyle: {
                color: candles[i].close >= candles[i].open
                  ? `${theme.upColor}99`
                  : `${theme.downColor}99`,
              },
            })),
            barMaxWidth: 12,
          },
        ],
      };
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [candles, events, height, theme, vwapData, orderLines, enabledIndicators, signalMarkers, sentimentMarkers, trades, interval, chartType, reduceMotion]
  );

  // 드로잉을 현재 줌/팬 상태 기준 픽셀 좌표로 다시 그린다 — data 좌표(시각+가격)로
  // 저장해뒀다가 매번 봉 인덱스(Asia/Seoul 버킷)로 변환하므로 줌/팬·봉 간격 변경 뒤에도 같은 자리에 놓인다.
  const redrawDrawings = useCallback((chart: EChart) => {
    const st = latestRef.current;
    if (!st.candles.length || chart.isDisposed()) return;
    const times = st.candles.map(c => c.time);
    const idxOf = (t: number) => fractionalIndexAt(times, t, st.interval);
    const toPx = (p: DrawingPoint): [number, number] => [xPixel(chart, idxOf(p.time)), yPixel(chart, p.price)];
    const gridRight = chart.getWidth() - GRID_RIGHT;
    const gridBottom = height - 104; // grid[0].bottom = height - CHART_H + 4, CHART_H = height - 100
    // 도구가 꺼져 있고 잠금이 아닐 때만 드로잉을 끌어서 옮기거나 클릭해 지울 수 있다
    const editable = !st.drawingsLocked && !st.activeDrawingTool;

    const moveDrawing = (id: string, dx: number, dy: number) => {
      const cur = latestRef.current;
      const d = cur.drawings.find(x => x.id === id);
      const ppb = pxPerBar(chart);
      if (!d || ppb == null || (dx === 0 && dy === 0)) return;
      const ts = cur.candles.map(c => c.time);
      // 추세선·텍스트는 봉 단위로 옮겨 봉에 붙은 채로 두고, 펜은 픽셀 그대로 옮긴다. 수평선은 가격만.
      const dIndex = d.tool === "HORIZONTAL_LINE" ? 0 : d.tool === "PEN" ? dx / ppb : Math.round(dx / ppb);
      const points = shiftPoints(
        d.points,
        t => fractionalIndexAt(ts, t, cur.interval),
        i => timeAtIndex(ts, i, cur.interval),
        dIndex,
        price => priceAtPixel(chart, yPixel(chart, price) + dy) ?? price,
      );
      cur.onDrawingsChange?.(cur.drawings.map(x => (x.id === id ? { ...x, points } : x)));
    };

    const elements: Record<string, unknown>[] = [];
    for (const d of st.drawings) {
      if (!isDrawingMeaningful(d, st.interval)) continue;
      const common = {
        id: d.id,
        z: 50,
        x: 0, y: 0, // 끌어서 옮긴 뒤 새 좌표로 다시 그릴 때 이동량을 되돌린다
        draggable: editable,
        cursor: editable ? "move" : st.activeDrawingTool ? "crosshair" : "default",
        onclick: editable
          ? () => {
              if (Date.now() - draggedAtRef.current < 300) return;
              const cur = latestRef.current;
              cur.onDrawingsChange?.(cur.drawings.filter(x => x.id !== d.id));
            }
          : undefined,
        // zrender는 draggable 요소를 누르기만 해도(mousedown) dragstart를, 떼면 dragend를 보낸다 — 움직이지
        // 않은 클릭에도 오므로 "방금 끌었다"는 실제로 옮겨졌을 때만 기록한다. 그렇지 않으면 뒤따르는
        // click이 항상 무시돼 클릭으로 지우기가 동작하지 않는다.
        ondragend(this: { x: number; y: number }) {
          if (!this.x && !this.y) return;
          draggedAtRef.current = Date.now();
          moveDrawing(d.id, this.x, this.y);
        },
      };
      if (d.tool === "HORIZONTAL_LINE") {
        const [, y] = toPx(d.points[0]);
        elements.push({ ...common, type: "line", shape: { x1: GRID_LEFT, y1: y, x2: gridRight, y2: y }, style: { stroke: theme.text, lineWidth: 1.5, lineDash: [4, 3] } });
      } else if (d.tool === "TREND_LINE") {
        const [x1, y1] = toPx(d.points[0]);
        const [x2, y2] = toPx(d.points[1]);
        elements.push({ ...common, type: "line", shape: { x1, y1, x2, y2 }, style: { stroke: DRAW_COLORS.trend, lineWidth: 2 } });
      } else if (d.tool === "PEN") {
        elements.push({ ...common, type: "polyline", shape: { points: d.points.map(toPx) }, style: { stroke: DRAW_COLORS.pen, lineWidth: 2, fill: null } });
      } else if (d.tool === "TEXT") {
        const [x, y] = toPx(d.points[0]);
        elements.push({
          ...common, type: "text",
          style: { text: d.text ?? "", x, y, fill: theme.text, font: "12px Pretendard, sans-serif", backgroundColor: `${theme.bg}cc`, padding: [2, 4], verticalAlign: "bottom" },
        });
      }
    }

    // ── 미리보기(저장하지 않음) ──
    const tool = st.activeDrawingTool;
    const pending = pendingPointRef.current;
    const hover = hoverRef.current;
    const ptPx = (p: ChartPoint): [number, number] => [xPixel(chart, p.index), yPixel(chart, p.price)];
    if (pending && hover && tool === "TREND_LINE") {
      const [x1, y1] = ptPx(pending);
      const [x2, y2] = ptPx(hover);
      elements.push({ type: "line", silent: true, z: 51, shape: { x1, y1, x2, y2 }, style: { stroke: DRAW_COLORS.trend, lineWidth: 1.5, lineDash: [4, 3] } });
    }
    if (pending && hover && tool === "ZOOM") {
      const xa = ptPx(pending)[0], xb = ptPx(hover)[0];
      elements.push({
        type: "rect", silent: true, z: 51,
        shape: { x: Math.min(xa, xb), y: GRID_TOP, width: Math.abs(xb - xa), height: Math.max(0, gridBottom - GRID_TOP) },
        style: { fill: `${DRAW_COLORS.zoom}22`, stroke: DRAW_COLORS.zoom, lineWidth: 1 },
      });
    }
    const m = tool === "MEASURE" ? (measureRef.current ?? (pending && hover ? { a: pending, b: hover } : null)) : null;
    if (m) {
      const [xa, ya] = ptPx(m.a);
      const [xb, yb] = ptPx(m.b);
      const r = measure(m.a, m.b);
      const color = r.priceDiff >= 0 ? theme.upColor : theme.downColor;
      const text = formatMeasure(r);
      // 마지막 봉 근처까지 재면 라벨이 오른쪽 가격축 밖으로 잘린다 — 넘치면 점 왼쪽으로 넘긴다
      const label = placeLabel(xb, yb, estimateLabelWidth(text, 11, 12), 11 + 6, chart.getWidth(), height);
      elements.push(
        { type: "rect", silent: true, z: 51, shape: { x: Math.min(xa, xb), y: Math.min(ya, yb), width: Math.abs(xb - xa), height: Math.abs(yb - ya) }, style: { fill: `${color}26`, stroke: color, lineWidth: 1 } },
        { type: "line", silent: true, z: 51, shape: { x1: xa, y1: ya, x2: xb, y2: yb }, style: { stroke: color, lineWidth: 1, lineDash: [3, 3] } },
        {
          type: "text", silent: true, z: 52,
          style: { text, x: label.x, y: label.y, align: label.align, fill: theme.bg, font: "bold 11px Pretendard, sans-serif", backgroundColor: color, padding: [3, 6], borderRadius: 3, verticalAlign: "middle" },
        },
      );
    }
    const pen = penRef.current;
    if (pen && pen.points.length > 1) {
      elements.push({ type: "polyline", silent: true, z: 51, shape: { points: pen.points.map(toPx) }, style: { stroke: DRAW_COLORS.pen, lineWidth: 2, fill: null } });
    }

    // graphic 컴포넌트를 통째로 갈아 끼운다(replaceMerge) — merge면 지운 드로잉이 화면에 남는다.
    // 그룹에 `$action: "replace"`를 쓰면 안 된다: ECharts 6에서 같은 그룹을 다시 replace할 때 글자가 바뀐
    // text 요소는 화면(zrender)에서 빠진다 — 옵션에는 남아 있는데, 마우스를 따라 바뀌는 측정 미리보기 라벨과
    // 두 번째 측정 결과 라벨이 보이지 않았다(e2e chart-drawing.spec.ts에서 실제 캔버스로 확인).
    chart.setOption(
      { graphic: { elements: [{ id: DRAWINGS_GROUP, type: "group", children: elements }] } } as never,
      { replaceMerge: ["graphic"] },
    );
  }, [theme, height]);

  useEffect(() => {
    if (!containerRef.current || candles.length === 0) return;
    let disposed = false;
    let removeResize: (() => void) | undefined;

    loadECharts().then((echarts) => {
      if (disposed || !containerRef.current) return;
      if (chartRef.current) { chartRef.current.dispose(); }

      const chart = echarts.init(containerRef.current, undefined, {
        renderer: "canvas",
        width:  containerRef.current.clientWidth,
        height,
      });
      chartRef.current = chart;

      const opt = buildOption();
      if (opt) chart.setOption(opt as Parameters<typeof chart.setOption>[0]);
      redrawDrawings(chart);

      chart.on("click", (params) => {
        const p = params as { componentType?: string; data?: { eventId?: number; orderId?: number } };
        if (p.componentType === "markPoint" && p.data?.eventId != null) {
          latestRef.current.onEventClick?.(p.data.eventId);
        }
        if (p.componentType === "markLine" && p.data?.orderId != null) {
          latestRef.current.onCancelOrderLine?.(p.data.orderId);
        }
      });

      const inGrid = (x: number, y: number) => {
        try { return chart.containPixel({ gridIndex: 0 }, [x, y]); } catch { return false; }
      };
      const addDrawing = (d: Drawing) => {
        const st = latestRef.current;
        st.onDrawingsChange?.([...st.drawings, d]);
      };
      const finishPen = () => {
        const pen = penRef.current;
        penRef.current = null;
        if (pen && pen.points.length > 1) {
          addDrawing({ id: newDrawingId(), tool: "PEN", points: decimate(pen.points, DRAWING_LIMITS.maxPenPoints) });
        } else if (pen) {
          redrawDrawings(chart);
        }
      };

      // 드로잉 도구가 켜져 있을 때만 캔버스 클릭을 "점 찍기"로 해석한다 —
      // activeDrawingTool 체크로 평소 줌/팬/툴팁 동작과 충돌하지 않게 막는다.
      // (캔들 바로 위를 클릭해 점을 찍는 게 흔한 사용 패턴이라 target 유무로
      // 거르지 않는다 — 마커/주문선을 정확히 겹쳐 클릭하는 드문 경우에만
      // 위 markPoint/markLine 핸들러와 함께 드로잉 점도 찍히는 것을 감수한다.)
      const zr = chart.getZr();
      zr.on("click", (event: unknown) => {
        const st = latestRef.current;
        const tool = st.activeDrawingTool;
        if (!tool || tool === "PEN") return;
        const { offsetX: x, offsetY: y } = event as { offsetX: number; offsetY: number };
        if (!inGrid(x, y)) return;
        const pt = pointAt(chart, st, x, y, true);
        if (!pt) return;
        const data = { time: pt.time, price: pt.price };

        if (tool === "HORIZONTAL_LINE") {
          addDrawing({ id: newDrawingId(), tool, points: [data] });
        } else if (tool === "TEXT") {
          setTextDraft({ left: x, top: y, point: data });
        } else if (tool === "TREND_LINE") {
          // 첫 클릭은 시작점만 기억, 두 번째 클릭에서 완성
          const first = pendingPointRef.current;
          if (!first) pendingPointRef.current = pt;
          else {
            pendingPointRef.current = null;
            hoverRef.current = null;
            addDrawing({ id: newDrawingId(), tool, points: [{ time: first.time, price: first.price }, data] });
          }
        } else if (tool === "MEASURE") {
          const first = pendingPointRef.current;
          if (!first) { pendingPointRef.current = pt; measureRef.current = null; }
          else { measureRef.current = { a: first, b: pt }; pendingPointRef.current = null; hoverRef.current = null; }
        } else if (tool === "ZOOM") {
          const first = pendingPointRef.current;
          if (!first) pendingPointRef.current = pt;
          else {
            pendingPointRef.current = null;
            hoverRef.current = null;
            const w = zoomWindow(first.index, pt.index, st.candles.length);
            if (w) chart.dispatchAction({ type: "dataZoom", dataZoomIndex: 0, startValue: w.startValue, endValue: w.endValue });
            st.onDrawingToolDone?.();
          }
        }
        redrawDrawings(chart);
      });

      zr.on("mousedown", (event: unknown) => {
        const st = latestRef.current;
        if (st.activeDrawingTool !== "PEN") return;
        const { offsetX: x, offsetY: y } = event as { offsetX: number; offsetY: number };
        if (!inGrid(x, y)) return;
        const pt = pointAt(chart, st, x, y, false);
        if (pt) penRef.current = { points: [{ time: pt.time, price: pt.price }], lastPx: [x, y] };
      });
      zr.on("mousemove", (event: unknown) => {
        const st = latestRef.current;
        const { offsetX: x, offsetY: y } = event as { offsetX: number; offsetY: number };
        const pen = penRef.current;
        if (pen) {
          if (Math.hypot(x - pen.lastPx[0], y - pen.lastPx[1]) < PEN_MIN_PX) return;
          const pt = pointAt(chart, st, x, y, false);
          if (!pt) return;
          pen.points.push({ time: pt.time, price: pt.price });
          pen.lastPx = [x, y];
          redrawDrawings(chart);
          return;
        }
        if (pendingPointRef.current && st.activeDrawingTool && st.activeDrawingTool !== "PEN") {
          hoverRef.current = pointAt(chart, st, x, y, true);
          redrawDrawings(chart);
        }
      });
      zr.on("mouseup", finishPen);
      zr.on("globalout", finishPen);

      chart.on("datazoom", () => {
        try {
          const dz = (chart.getOption() as { dataZoom?: Array<{ start?: number; end?: number }> }).dataZoom?.[0];
          const first = latestRef.current.candles[0]?.time;
          if (dz && first != null && dz.start != null && dz.end != null) zoomRef.current = { firstTime: first, start: dz.start, end: dz.end };
        } catch { /* 줌 상태를 못 읽으면 다음 재생성 때 기본 구간 */ }
        redrawDrawings(chart);
      });

      const onResize = () => {
        if (containerRef.current && chartRef.current && !chartRef.current.isDisposed()) {
          chartRef.current.resize({ width: containerRef.current.clientWidth });
          redrawDrawings(chartRef.current);
        }
      };
      window.addEventListener("resize", onResize);
      removeResize = () => window.removeEventListener("resize", onResize);
    });

    return () => {
      disposed = true;
      removeResize?.();
      if (chartRef.current && !chartRef.current.isDisposed()) {
        chartRef.current.dispose();
        chartRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candles, events, height, theme, buildOption, redrawDrawings]);

  // 도구를 바꾸면(Esc 포함) 그리던 중인 점·측정·텍스트 입력을 버리고, 펜일 때만 드래그 팬을 끈다.
  useEffect(() => {
    pendingPointRef.current = null;
    hoverRef.current = null;
    measureRef.current = null;
    penRef.current = null;
    setTextDraft(null);
    const chart = chartRef.current;
    if (chart && !chart.isDisposed()) {
      chart.setOption({ dataZoom: [{ id: "mt-inside", moveOnMouseMove: activeDrawingTool !== "PEN" }] } as never);
    }
  }, [activeDrawingTool]);

  // 드로잉 목록·잠금·도구·간격이 바뀌었을 때는 차트를 재생성하지 않고 다시 그리기만 한다.
  useEffect(() => {
    if (chartRef.current && !chartRef.current.isDisposed()) redrawDrawings(chartRef.current);
  }, [drawings, drawingsLocked, activeDrawingTool, interval, redrawDrawings]);

  const commitText = (value: string) => {
    const draft = textDraft;
    setTextDraft(null);
    const text = value.trim().slice(0, DRAWING_LIMITS.maxText);
    if (!draft || !text) return;
    const st = latestRef.current;
    st.onDrawingsChange?.([...st.drawings, { id: newDrawingId(), tool: "TEXT", points: [draft.point], text }]);
  };

  return (
    <div className="relative w-full" style={{ height }}>
      <div
        ref={containerRef}
        className="w-full rounded-lg overflow-hidden border border-gray-200 dark:border-dracula-line"
        style={{ height, cursor: activeDrawingTool ? "crosshair" : undefined }}
      />
      {textDraft && (
        <input
          // 클릭한 자리에 바로 입력 — Enter로 추가, Esc로 취소, 바깥을 누르면 입력한 내용으로 추가
          autoFocus
          type="text"
          aria-label="차트에 넣을 텍스트 (Enter 추가, Esc 취소)"
          maxLength={DRAWING_LIMITS.maxText}
          placeholder="텍스트 입력 후 Enter"
          className="absolute z-10 w-44 rounded border border-tm-line2 bg-tm-panel px-1.5 py-0.5 text-xs text-dracula-fg outline-none focus:border-dracula-purple"
          style={{ left: Math.max(0, textDraft.left - 4), top: Math.max(0, textDraft.top - 24) }}
          onKeyDown={(e) => {
            if (e.key === "Enter") { e.preventDefault(); commitText(e.currentTarget.value); }
            else if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); setTextDraft(null); }
          }}
          onBlur={(e) => commitText(e.currentTarget.value)}
        />
      )}
    </div>
  );
}

// ── 픽셀 ↔ 데이터 좌표 ───────────────────────────────────────
type EChart = import("echarts").ECharts;

function xPixel(chart: EChart, index: number): number {
  return chart.convertToPixel({ xAxisIndex: 0 }, index) as number;
}
function yPixel(chart: EChart, price: number): number {
  return chart.convertToPixel({ yAxisIndex: 0 }, price) as number;
}
/** 봉 하나의 픽셀 폭(현재 줌 기준). 계산할 수 없으면 null */
function pxPerBar(chart: EChart): number | null {
  const d = xPixel(chart, 1) - xPixel(chart, 0);
  return Number.isFinite(d) && d !== 0 ? d : null;
}
function priceAtPixel(chart: EChart, y: number): number | null {
  const v = Number(chart.convertFromPixel({ yAxisIndex: 0 }, y));
  return Number.isFinite(v) ? v : null;
}

/**
 * 픽셀 위치의 차트 점. snap이면 가장 가까운 봉에 붙이고(자석이면 가격도 그 봉의 OHLC로),
 * 아니면(펜) 봉 사이 위치를 그대로 시각으로 보간한다.
 */
function pointAt(
  chart: EChart,
  st: { candles: CandleData[]; interval: ChartInterval; magnet: boolean },
  x: number, y: number, snap: boolean,
): ChartPoint | null {
  const n = st.candles.length;
  const ppb = pxPerBar(chart);
  let price = priceAtPixel(chart, y);
  if (!n || price == null) return null;
  const fi = ppb == null ? 0 : (x - xPixel(chart, 0)) / ppb;
  if (!Number.isFinite(fi)) return null;
  const times = st.candles.map(c => c.time);
  if (snap) {
    const idx = Math.max(0, Math.min(n - 1, Math.round(fi)));
    if (st.magnet) price = magnetSnap(st.candles[idx], price);
    return { index: idx, price, time: times[idx] };
  }
  return { index: fi, price, time: timeAtIndex(times, fi, st.interval) };
}

function newDrawingId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
}
