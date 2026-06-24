"use client";

import { useEffect, useRef } from "react";
import { useTheme } from "next-themes";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import type { CandleData } from "./chart/types";

let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

function fmtVol(v: number) {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000)     return `${(v / 1_000).toFixed(0)}K`;
  return String(v);
}

interface Props {
  candles: CandleData[];
  height?: number;
}

export default function VolumeChart({ candles, height = 140 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<import("echarts").ECharts | null>(null);
  const { resolvedTheme } = useTheme();
  const { chartTheme } = useThemeStore();
  const ct = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;

  useEffect(() => {
    if (!containerRef.current || candles.length === 0) return;
    let disposed = false;

    loadECharts().then((echarts) => {
      if (disposed || !containerRef.current) return;
      if (chartRef.current) chartRef.current.dispose();

      const isDark  = resolvedTheme === "dark";
      const bg      = isDark ? "#1e1f29" : "#ffffff";
      const grid    = isDark ? "#44475a" : "#e5e7eb";
      const text    = isDark ? "#6272a4" : "#6b7280";
      const upColor = `${ct.upColor}cc`;
      const dnColor = `${ct.downColor}cc`;

      const chart = echarts.init(containerRef.current, undefined, {
        renderer: "canvas",
        width:    containerRef.current.clientWidth,
        height,
      });
      chartRef.current = chart;

      const maxVol = Math.max(...candles.map(c => c.volume ?? 0));

      chart.setOption({
        backgroundColor: bg,
        animation: false,
        tooltip: {
          trigger: "axis",
          backgroundColor: isDark ? "#282a36" : "#fff",
          borderColor: grid,
          textStyle: { color: text },
          formatter: (params: unknown[]) => {
            const p = (params as { dataIndex: number }[])[0];
            if (!p) return "";
            const c = candles[p.dataIndex];
            return [
              `<b>${new Date(c.time * 1000).toLocaleDateString("ko-KR")}</b>`,
              `거래량: <b>${fmtVol(c.volume ?? 0)}</b>`,
            ].join("<br/>");
          },
        },
        grid: { left: 8, right: 72, top: 8, bottom: 28 },
        xAxis: {
          type: "category",
          data: candles.map(c =>
            new Date(c.time * 1000).toISOString().slice(0, 10)
          ),
          axisLabel: {
            show: true, color: text, fontSize: 10,
            formatter: (v: string) => v.slice(5),
          },
          axisLine:  { lineStyle: { color: grid } },
          splitLine: { show: false },
        },
        yAxis: {
          type: "value",
          position: "right",
          axisLabel: { color: text, fontSize: 10, formatter: fmtVol },
          axisLine:  { show: false },
          splitLine: { lineStyle: { color: grid, type: "dashed", opacity: 0.5 } },
        },
        dataZoom: [
          { type: "inside", start: 60, end: 100 },
        ],
        series: [{
          type: "bar",
          barMaxWidth: 14,
          barGap: "0%",
          barCategoryGap: "0%",
          data: candles.map(c => ({
            value: c.volume ?? 0,
            itemStyle: {
              color: c.close >= c.open ? upColor : dnColor,
              // 최대 거래량 강조
              borderRadius: c.volume === maxVol ? [3, 3, 0, 0] : 0,
            },
          })),
          emphasis: {
            itemStyle: { opacity: 1 },
          },
        }],
      });

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
  }, [candles, height, resolvedTheme, chartTheme]);

  if (candles.length === 0) return null;

  return (
    <div className="border border-gray-200 dark:border-[#44475a] rounded-lg overflow-hidden">
      <div className="px-4 pt-3 pb-1 flex items-center justify-between">
        <span className="text-xs font-medium text-gray-500 dark:text-[#6272a4]">거래량</span>
        <span className="text-xs text-gray-400 dark:text-[#44475a]">
          최대 {fmtVol(Math.max(...candles.map(c => c.volume ?? 0)))}
        </span>
      </div>
      <div ref={containerRef} style={{ height }} />
    </div>
  );
}
