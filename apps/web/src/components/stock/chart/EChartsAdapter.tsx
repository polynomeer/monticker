"use client";

/**
 * Apache ECharts 기반 캔들스틱 차트 어댑터.
 * TradingView/lightweight-charts 와 무관 — Apache 2.0 라이선스.
 *
 * 교체 방법: 이 파일을 새 어댑터로 교체하고 StockChart.tsx의
 * import 한 줄만 바꾸면 됩니다.
 */

import { useEffect, useRef } from "react";
import type { ChartAdapterProps } from "./types";

// ECharts는 번들 크기 절약을 위해 동적 import
let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

const EVENT_COLORS: Record<string, string> = {
  PRICE_SPIKE:          "#0ecb81",
  PRICE_DROP:           "#f6465d",
  VOLUME_SURGE:         "#f1fa8c",
  DISCLOSURE_PUBLISHED: "#bd93f9",
  default:              "#6272a4",
};

export default function EChartsAdapter({
  candles,
  events = [],
  height = 300,
  theme,
}: ChartAdapterProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<import("echarts").ECharts | null>(null);

  useEffect(() => {
    if (!containerRef.current || candles.length === 0) return;

    let disposed = false;

    loadECharts().then((echarts) => {
      if (disposed || !containerRef.current) return;

      // dispose previous instance
      if (chartRef.current) chartRef.current.dispose();

      const chart = echarts.init(containerRef.current, undefined, {
        renderer: "canvas",
        width: containerRef.current.clientWidth,
        height,
      });
      chartRef.current = chart;

      // 마커: 이벤트를 날짜 기반 markPoint로 변환
      const markPoints = events.map((e) => ({
        name: e.eventType,
        coord: [
          new Date(e.time * 1000).toISOString().slice(0, 16).replace("T", " "),
          candles.find((c) => Math.abs(c.time - e.time) < 120)?.high ?? 0,
        ],
        value: e.title.slice(0, 8),
        itemStyle: { color: EVENT_COLORS[e.eventType] ?? EVENT_COLORS.default },
        symbolSize: e.importanceScore > 70 ? 12 : 8,
      }));

      const option: import("echarts").EChartsOption = {
        backgroundColor: theme.bg,
        animation: false,
        tooltip: {
          trigger: "axis",
          axisPointer: { type: "cross" },
          backgroundColor: theme.bg === "#1e1f29" ? "#282a36" : "#fff",
          borderColor: "#44475a",
          textStyle: { color: theme.text },
        },
        grid: [
          { left: 60, right: 16, top: 16, bottom: 80 },
          { left: 60, right: 16, top: height - 80, bottom: 20 },
        ],
        xAxis: [
          {
            type: "category",
            data: candles.map((c) =>
              new Date(c.time * 1000).toISOString().slice(0, 16).replace("T", " ")
            ),
            axisLabel: {
              color: theme.text,
              fontSize: 11,
              formatter: (v: string) => v.slice(5, 16),
            },
            axisLine:  { lineStyle: { color: theme.grid } },
            splitLine: { show: false },
          },
          {
            type: "category",
            gridIndex: 1,
            data: candles.map((c) =>
              new Date(c.time * 1000).toISOString().slice(0, 16).replace("T", " ")
            ),
            axisLabel: { show: false },
            axisLine:  { lineStyle: { color: theme.grid } },
            splitLine: { show: false },
          },
        ],
        yAxis: [
          {
            scale: true,
            axisLabel: {
              color: theme.text,
              fontSize: 11,
              formatter: (v: number) =>
                v >= 1000 ? `${(v / 1000).toFixed(0)}K` : String(v),
            },
            axisLine:  { lineStyle: { color: theme.grid } },
            splitLine: { lineStyle: { color: theme.grid, type: "dashed" } },
          },
          {
            scale: true,
            gridIndex: 1,
            axisLabel: {
              color: theme.text,
              fontSize: 10,
              formatter: (v: number) =>
                v >= 1000000 ? `${(v / 1000000).toFixed(1)}M` : `${(v / 1000).toFixed(0)}K`,
            },
            axisLine:  { lineStyle: { color: theme.grid } },
            splitLine: { show: false },
          },
        ],
        dataZoom: [
          { type: "inside", xAxisIndex: [0, 1], start: 60, end: 100 },
          {
            type: "slider",
            xAxisIndex: [0, 1],
            bottom: 4,
            height: 20,
            borderColor: theme.grid,
            backgroundColor: theme.bg,
            fillerColor: `${theme.grid}44`,
            handleStyle: { color: theme.grid },
            textStyle: { color: theme.text },
          },
        ],
        series: [ // eslint-disable-next-line @typescript-eslint/no-explicit-any
          {
            type: "candlestick",
            xAxisIndex: 0,
            yAxisIndex: 0,
            data: candles.map((c) => [c.open, c.close, c.low, c.high]),
            itemStyle: {
              color:        theme.upColor,
              color0:       theme.downColor,
              borderColor:  theme.upColor,
              borderColor0: theme.downColor,
            },
            markPoint: {
              data: markPoints,
              label: { show: true, fontSize: 9, color: "#fff", distance: 4 },
            },
          },
          {
            type: "bar",
            xAxisIndex: 1,
            yAxisIndex: 1,
            data: candles.map((c) => ({
              value: c.volume ?? 0,
              itemStyle: {
                color: c.close >= c.open ? `${theme.upColor}99` : `${theme.downColor}99`,
              },
            })),
          },
        ],
      };

      chart.setOption(option as Parameters<typeof chart.setOption>[0]);

      const onResize = () => {
        if (containerRef.current && chartRef.current) {
          chartRef.current.resize({
            width: containerRef.current.clientWidth,
          });
        }
      };
      window.addEventListener("resize", onResize);
      return () => window.removeEventListener("resize", onResize);
    });

    return () => {
      disposed = true;
      if (chartRef.current) {
        chartRef.current.dispose();
        chartRef.current = null;
      }
    };
  }, [candles, events, height, theme]);

  return (
    <div
      ref={containerRef}
      className="w-full rounded-lg overflow-hidden border border-gray-200 dark:border-[#44475a]"
      style={{ height }}
    />
  );
}
