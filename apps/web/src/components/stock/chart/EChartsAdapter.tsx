"use client";

/**
 * Apache ECharts 기반 프로 차트 어댑터.
 * TradingView 스타일: MA5/MA20, 현재가 라인, OHLCV 툴팁, 거래량 패널, 줌/패닝.
 */

import { useEffect, useRef, useCallback } from "react";
import type { ChartAdapterProps, CandleData } from "./types";

let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

// ── 이동평균 계산 ────────────────────────────────────────────
function calcMA(data: CandleData[], period: number): (number | null)[] {
  return data.map((_, i) => {
    if (i < period - 1) return null;
    const slice = data.slice(i - period + 1, i + 1);
    return slice.reduce((s, c) => s + c.close, 0) / period;
  });
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

export default function EChartsAdapter({
  candles,
  events = [],
  height = 420,
  theme,
}: ChartAdapterProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<import("echarts").ECharts | null>(null);

  const buildOption = useCallback(
    (echarts: typeof import("echarts")) => {
      if (!candles.length) return null;

      const dates  = candles.map(c => fmtTime(c.time));
      const ohlcv  = candles.map(c => [c.open, c.close, c.low, c.high]);
      const vols   = candles.map(c => c.volume ?? 0);
      const ma5    = calcMA(candles, 5);
      const ma20   = calcMA(candles, 20);
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
          };
        })
        .filter(Boolean);

      const CHART_H  = height - 100; // 캔들 패널 높이
      const VOL_H    = 90;           // 볼륨 패널 높이
      const VOL_TOP  = CHART_H + 8;

      return {
        backgroundColor: theme.bg,
        animation: false,

        // ── 범례 ────────────────────────────────────────────
        legend: {
          top: 6, left: 8,
          icon: "line",
          itemWidth: 16, itemHeight: 2,
          textStyle: { color: theme.text, fontSize: 11 },
          data: ["MA5", "MA20"],
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
          { left: 16, right: 82, top: 32, bottom: height - CHART_H + 4 },
          { left: 16, right: 82, top: VOL_TOP, bottom: 52 },
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
            // 현재가 수평선
            markLine: {
              silent: true,
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
              data: [{ yAxis: last.close }] as never[],
            },
          },

          // MA5
          {
            name: "MA5",
            type: "line",
            xAxisIndex: 0, yAxisIndex: 0,
            data: ma5,
            smooth: true,
            symbol: "none",
            lineStyle: { color: "#f1fa8c", width: 1.5, opacity: 0.9 },
            tooltip: { show: false },
          },

          // MA20
          {
            name: "MA20",
            type: "line",
            xAxisIndex: 0, yAxisIndex: 0,
            data: ma20,
            smooth: true,
            symbol: "none",
            lineStyle: { color: "#8be9fd", width: 1.5, opacity: 0.9 },
            tooltip: { show: false },
          },

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
      } satisfies Parameters<typeof echarts.init>[0] extends never ? never : object;
    },
    [candles, events, height, theme]
  );

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

      const opt = buildOption(echarts);
      if (opt) chart.setOption(opt as Parameters<typeof chart.setOption>[0]);

      const onResize = () => {
        if (containerRef.current && chartRef.current && !chartRef.current.isDisposed()) {
          chartRef.current.resize({ width: containerRef.current.clientWidth });
        }
      };
      window.addEventListener("resize", onResize);
      return () => window.removeEventListener("resize", onResize);
    });

    return () => {
      disposed = true;
      if (chartRef.current && !chartRef.current.isDisposed()) {
        chartRef.current.dispose();
        chartRef.current = null;
      }
    };
  }, [candles, events, height, theme, buildOption]);

  return (
    <div
      ref={containerRef}
      className="w-full rounded-lg overflow-hidden border border-gray-200 dark:border-[#44475a]"
      style={{ height }}
    />
  );
}
