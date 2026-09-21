"use client";

/**
 * Apache ECharts 기반 프로 차트 어댑터.
 * TradingView 스타일: MA5/MA20, 현재가 라인, OHLCV 툴팁, 거래량 패널, 줌/패닝.
 * 고급 모드: 자유 지표(MA60/볼린저밴드), 실전투자 미체결 주문선, 추세선/수평선 드로잉.
 */

import { useEffect, useRef, useCallback } from "react";
import type { ChartAdapterProps, CandleData, IndicatorKey, Drawing } from "./types";

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
const EVENT_COLORS: Record<string, string> = {
  PRICE_SPIKE:          "#0ecb81",
  PRICE_DROP:           "#f6465d",
  VOLUME_SURGE:         "#f1fa8c",
  DISCLOSURE_PUBLISHED: "#bd93f9",
  default:              "#6272a4",
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
function fmtTime(epoch: number): string {
  return new Date(epoch * 1000).toISOString().slice(0, 10);
}

// 가장 가까운 캔들의 날짜 문자열로 변환 — 드로잉이 저장된 (time,price)를
// 다시 픽셀로 그릴 때 category 축이 요구하는 값(날짜 문자열)으로 맞춘다.
function nearestDateStr(candles: CandleData[], dates: string[], time: number): string {
  let idx = candles.findIndex(c => c.time >= time);
  if (idx < 0) idx = candles.length - 1;
  return dates[idx];
}

function resolveCategoryTime(value: unknown, candles: CandleData[], dates: string[]): number {
  if (typeof value === "number") {
    const idx = Math.round(value);
    return candles[Math.max(0, Math.min(candles.length - 1, idx))]?.time ?? candles[0].time;
  }
  const idx = dates.indexOf(String(value));
  return candles[idx >= 0 ? idx : candles.length - 1].time;
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
  activeDrawingTool = null,
  drawings = [],
  onDrawingsChange,
}: ChartAdapterProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<import("echarts").ECharts | null>(null);
  const pendingPointRef = useRef<{ time: number; price: number } | null>(null);

  // 콜백/드로잉 목록은 매 렌더 새 값이 올 수 있어 이벤트 핸들러 안에서는 항상
  // 최신 값을 ref로 읽는다 — 차트 인스턴스를 매번 재생성하지 않기 위함.
  const latestRef = useRef({ activeDrawingTool, drawings, onDrawingsChange, onEventClick, onCancelOrderLine, candles });
  latestRef.current = { activeDrawingTool, drawings, onDrawingsChange, onEventClick, onCancelOrderLine, candles };

  const indicators: Set<IndicatorKey> = new Set(enabledIndicators ?? ["MA5", "MA20"]);

  const buildOption = useCallback(
    () => {
      if (!candles.length) return null;

      const dates  = candles.map(c => fmtTime(c.time));
      const ohlcv  = candles.map(c => [c.open, c.close, c.low, c.high]);
      const vols   = candles.map(c => c.volume ?? 0);
      const last   = candles[candles.length - 1];
      const prev   = candles[candles.length - 2];
      const chg    = prev ? ((last.close - prev.close) / prev.close) * 100 : 0;
      const isUp   = last.close >= last.open;

      // 이벤트 마커 (markPoint)
      const markData = events
        .map(e => {
          const idx = candles.findIndex(c => Math.abs(c.time - e.time) < 90);
          if (idx < 0) return null;
          return {
            name:  e.eventType,
            coord: [dates[idx], candles[idx].high],
            value: e.title.slice(0, 6),
            itemStyle: { color: EVENT_COLORS[e.eventType] ?? EVENT_COLORS.default },
            symbolSize: e.importanceScore > 70 ? 14 : 9,
            // markPoint 데이터 항목에 자유 필드를 얹어두면 클릭 이벤트의 params.data로
            // 그대로 돌아온다 — 이벤트 타임라인으로 점프할 때 이 id로 정확히 매칭한다.
            eventId: e.id,
          };
        })
        .filter(Boolean);

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
        animation: false,

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
          axisPointer: { type: "cross", crossStyle: { color: theme.text } },
          backgroundColor: theme.bg === "#ffffff" ? "#f8f8f2" : "#282a36",
          borderColor: theme.grid,
          padding: [8, 12],
          textStyle: { color: theme.text, fontSize: 12 },
          formatter(params: unknown[]) {
            const ps = params as { seriesType: string; value: unknown; dataIndex: number }[];
            const cdl = ps.find(p => p.seriesType === "candlestick");
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
              `<b style="color:${theme.text}">${fmtTime(c.time)}</b>`,
              `<span style="color:${theme.text}">시가 </span><b style="color:${col}">${fmtPrice(c.open)}</b>`,
              `<span style="color:${theme.text}">고가 </span><b style="color:${theme.upColor}">${fmtPrice(c.high)}</b>`,
              `<span style="color:${theme.text}">저가 </span><b style="color:${theme.downColor}">${fmtPrice(c.low)}</b>`,
              `<span style="color:${theme.text}">종가 </span><b style="color:${col}">${fmtPrice(c.close)} <small>(${sign}${pct.toFixed(2)}%)</small></b>`,
              `<span style="color:${theme.text}">거래량 </span><b style="color:${theme.text}">${fmtVol(c.volume ?? 0)}</b>`,
            ].join("<br/>");
          },
        },

        // ── 그리드 ──────────────────────────────────────────
        grid: [
          { left: 48, right: 82, top: 32, bottom: height - CHART_H + 4 },
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
            type: "inside",
            xAxisIndex: [0, 1],
            start: Math.max(0, 100 - (30 / candles.length) * 100),
            end: 100,
            zoomOnMouseWheel: true,
            moveOnMouseMove: true,
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
            labelFormatter: (_: unknown, v: string) => v.slice(5),
            emphasis: {
              handleStyle: { color: theme.upColor },
              moveHandleStyle: { color: theme.upColor },
            },
          },
        ],

        // ── 시리즈 ──────────────────────────────────────────
        series: [
          // 캔들스틱
          {
            name: "OHLC",
            type: "candlestick",
            xAxisIndex: 0, yAxisIndex: 0,
            data: ohlcv,
            itemStyle: {
              color:        theme.upColor,
              color0:       theme.downColor,
              borderColor:  theme.upColor,
              borderColor0: theme.downColor,
              borderWidth: 1,
            },
            markPoint: {
              symbol: "pin",
              symbolSize: 28,
              data: markData as never[],
              label: { show: true, fontSize: 8, color: "#fff", fontWeight: "bold" },
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
    [candles, events, height, theme, vwapData, orderLines, enabledIndicators]
  );

  // 드로잉을 현재 줌/팬 상태 기준 픽셀 좌표로 다시 그린다 — data 좌표(시각+가격)로
  // 저장해뒀다가 매번 변환하므로 줌/팬을 해도 캔들에 고정된 채 따라온다.
  const redrawDrawings = useCallback((chart: import("echarts").ECharts) => {
    const { drawings: currentDrawings, onDrawingsChange: setDrawings, candles: currentCandles } = latestRef.current;
    if (!currentCandles.length) return;
    const dates = currentCandles.map(c => fmtTime(c.time));

    const toPixel = (p: { time: number; price: number }): [number, number] => {
      const dateStr = nearestDateStr(currentCandles, dates, p.time);
      const x = chart.convertToPixel({ xAxisIndex: 0 }, dateStr) as number;
      const y = chart.convertToPixel({ yAxisIndex: 0 }, p.price) as number;
      return [x, y];
    };

    const elements = currentDrawings.map(d => {
      const removeThis = () => setDrawings?.(currentDrawings.filter(x => x.id !== d.id));
      if (d.tool === "HORIZONTAL_LINE") {
        const [, y] = toPixel(d.points[0]);
        return {
          id: d.id, type: "line" as const,
          shape: { x1: 48, y1: y, x2: 9999, y2: y },
          style: { stroke: theme.text, lineWidth: 1.5, lineDash: [4, 3] },
          cursor: "pointer",
          onclick: removeThis,
          z: 50,
        };
      }
      const [x1, y1] = toPixel(d.points[0]);
      const [x2, y2] = toPixel(d.points[1]);
      return {
        id: d.id, type: "line" as const,
        shape: { x1, y1, x2, y2 },
        style: { stroke: "#ffb86c", lineWidth: 2 },
        cursor: "pointer",
        onclick: removeThis,
        z: 50,
      };
    });

    chart.setOption({ graphic: { elements: [{ type: "group", children: elements }] } } as never);
  }, [theme]);

  useEffect(() => {
    if (!containerRef.current || candles.length === 0) return;
    let disposed = false;

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

      // 드로잉 도구가 켜져 있을 때만 캔버스 클릭을 "점 찍기"로 해석한다 —
      // activeDrawingTool 체크로 평소 줌/팬/툴팁 동작과 충돌하지 않게 막는다.
      // (캔들 바로 위를 클릭해 점을 찍는 게 흔한 사용 패턴이라 target 유무로
      // 거르지 않는다 — 마커/주문선을 정확히 겹쳐 클릭하는 드문 경우에만
      // 위 markPoint/markLine 핸들러와 함께 드로잉 점도 찍히는 것을 감수한다.)
      chart.getZr().on("click", (event: unknown) => {
        const tool = latestRef.current.activeDrawingTool;
        if (!tool) return;
        const e = event as { offsetX: number; offsetY: number };
        const dataCoord = chart.convertFromPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [e.offsetX, e.offsetY]);
        if (!dataCoord) return;
        const { candles: currentCandles } = latestRef.current;
        const dates = currentCandles.map(c => fmtTime(c.time));
        const time = resolveCategoryTime(dataCoord[0], currentCandles, dates);
        const price = dataCoord[1] as number;

        if (tool === "HORIZONTAL_LINE") {
          const drawing: Drawing = { id: `${Date.now()}`, tool, points: [{ time, price }] };
          latestRef.current.onDrawingsChange?.([...latestRef.current.drawings, drawing]);
          return;
        }
        // TREND_LINE: 첫 클릭은 시작점만 기억, 두 번째 클릭에서 완성
        if (!pendingPointRef.current) {
          pendingPointRef.current = { time, price };
        } else {
          const drawing: Drawing = { id: `${Date.now()}`, tool, points: [pendingPointRef.current, { time, price }] };
          pendingPointRef.current = null;
          latestRef.current.onDrawingsChange?.([...latestRef.current.drawings, drawing]);
        }
      });

      chart.on("datazoom", () => redrawDrawings(chart));

      const onResize = () => {
        if (containerRef.current && chartRef.current && !chartRef.current.isDisposed()) {
          chartRef.current.resize({ width: containerRef.current.clientWidth });
          redrawDrawings(chartRef.current);
        }
      };
      window.addEventListener("resize", onResize);
      return () => window.removeEventListener("resize", onResize);
    });

    return () => {
      disposed = true;
      pendingPointRef.current = null;
      if (chartRef.current && !chartRef.current.isDisposed()) {
        chartRef.current.dispose();
        chartRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candles, events, height, theme, buildOption, redrawDrawings]);

  // 드로잉 목록만 바뀌었을 때(다른 항목 삭제 등)는 차트를 재생성하지 않고 다시 그리기만 한다.
  useEffect(() => {
    if (chartRef.current && !chartRef.current.isDisposed()) redrawDrawings(chartRef.current);
  }, [drawings, redrawDrawings]);

  return (
    <div
      ref={containerRef}
      className="w-full rounded-lg overflow-hidden border border-gray-200 dark:border-dracula-line"
      style={{ height, cursor: activeDrawingTool ? "crosshair" : undefined }}
    />
  );
}
