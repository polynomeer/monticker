"use client";

/**
 * ECharts 누적 호가 깊이 어댑터 — 매수(왼쪽, 상승색)·매도(오른쪽, 하락색) 계단 면적.
 * 호가는 1초마다 바뀌므로 차트는 테마·높이가 바뀔 때만 새로 만들고, 데이터는 setOption으로만 갱신한다.
 */

import { useEffect, useRef } from "react";
import type { DepthAdapterProps, DepthSeries } from "./types";

let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

function withAlpha(hex: string, alpha: number) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function dataOption(depth: DepthSeries) {
  return {
    series: [
      { id: "bids", data: depth.bids },
      { id: "asks", data: depth.asks },
    ],
  };
}

export default function EChartsDepthAdapter({ depth, height = 220, theme, priceDigits = 0 }: DepthAdapterProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<import("echarts").ECharts | null>(null);
  const depthRef = useRef(depth);
  depthRef.current = depth;

  useEffect(() => {
    if (!containerRef.current) return;
    let disposed = false;
    let onResize: (() => void) | null = null;

    loadECharts().then((echarts) => {
      if (disposed || !containerRef.current) return;
      const chart = echarts.init(containerRef.current, undefined, { renderer: "canvas", height });
      chartRef.current = chart;
      const fmtPrice = (v: number) => v.toLocaleString("ko-KR", { minimumFractionDigits: priceDigits, maximumFractionDigits: priceDigits });
      const series = (id: string, name: string, color: string) => ({
        id,
        name,
        type: "line" as const,
        step: "end" as const,
        symbol: "none",
        lineStyle: { color, width: 1.5 },
        areaStyle: { color: withAlpha(color, 0.25) },
        emphasis: { disabled: true },
        animation: false,
        data: [] as [number, number][],
      });
      chart.setOption({
        backgroundColor: "transparent",
        grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
        tooltip: {
          trigger: "axis",
          axisPointer: { type: "line", lineStyle: { color: theme.grid } },
          backgroundColor: theme.bg,
          borderColor: theme.grid,
          textStyle: { color: theme.text, fontSize: 12 },
          valueFormatter: (v: unknown) => (typeof v === "number" ? `${v.toLocaleString("ko-KR")}주` : String(v ?? "")),
        },
        xAxis: {
          type: "value",
          scale: true,
          axisLine: { lineStyle: { color: theme.grid } },
          axisLabel: { color: theme.text, fontSize: 10, formatter: fmtPrice, hideOverlap: true },
          splitLine: { show: false },
        },
        yAxis: {
          type: "value",
          axisLabel: { color: theme.text, fontSize: 10, formatter: (v: number) => v.toLocaleString("ko-KR", { notation: "compact" }) },
          splitLine: { lineStyle: { color: theme.grid } },
        },
        series: [series("bids", "매수 누적", theme.upColor), series("asks", "매도 누적", theme.downColor)],
      });
      chart.setOption(dataOption(depthRef.current));

      onResize = () => { if (!chart.isDisposed()) chart.resize(); };
      window.addEventListener("resize", onResize);
    });

    return () => {
      disposed = true;
      if (onResize) window.removeEventListener("resize", onResize);
      if (chartRef.current && !chartRef.current.isDisposed()) chartRef.current.dispose();
      chartRef.current = null;
    };
  }, [height, theme, priceDigits]);

  useEffect(() => {
    const chart = chartRef.current;
    if (chart && !chart.isDisposed()) chart.setOption(dataOption(depth));
  }, [depth]);

  return <div ref={containerRef} className="w-full" style={{ height }} role="img" aria-label="누적 호가 깊이 차트" />;
}
