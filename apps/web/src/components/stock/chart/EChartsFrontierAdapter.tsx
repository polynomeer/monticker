"use client";

/**
 * ECharts 위험-수익 산점도 어댑터 — 무작위 포트폴리오 표본, 효율적 프론티어 선, 샤프 비율 최대 지점 등.
 * 차트는 테마·높이가 바뀔 때만 새로 만들고, 데이터가 바뀌면 옵션 전체를 교체한다(계열 개수가 바뀔 수 있다).
 */

import { useEffect, useRef } from "react";
import type { FrontierAdapterProps } from "./types";
import { buildFrontierOption } from "./frontierOption";

let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

export default function EChartsFrontierAdapter({ data, height = 320, theme, reduceMotion = false }: FrontierAdapterProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<import("echarts").ECharts | null>(null);
  const dataRef = useRef(data);
  dataRef.current = data;

  useEffect(() => {
    if (!containerRef.current) return;
    let disposed = false;
    let onResize: (() => void) | null = null;

    loadECharts().then((echarts) => {
      if (disposed || !containerRef.current) return;
      const chart = echarts.init(containerRef.current, undefined, { renderer: "canvas", height });
      chartRef.current = chart;
      chart.setOption(buildFrontierOption(dataRef.current, theme, reduceMotion), { notMerge: true });
      onResize = () => { if (!chart.isDisposed()) chart.resize(); };
      window.addEventListener("resize", onResize);
    });

    return () => {
      disposed = true;
      if (onResize) window.removeEventListener("resize", onResize);
      if (chartRef.current && !chartRef.current.isDisposed()) chartRef.current.dispose();
      chartRef.current = null;
    };
  }, [height, theme, reduceMotion]);

  useEffect(() => {
    const chart = chartRef.current;
    if (chart && !chart.isDisposed()) chart.setOption(buildFrontierOption(data, theme, reduceMotion), { notMerge: true });
  }, [data, theme, reduceMotion]);

  return <div ref={containerRef} className="w-full" style={{ height }} role="img" aria-label="효율적 프론티어 — 무작위 포트폴리오 표본과 샤프 비율 최대 지점" />;
}
