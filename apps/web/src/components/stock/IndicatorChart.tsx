"use client";

/**
 * RSI / MACD 보조지표 차트 (Apache ECharts, 독립 컴포넌트)
 */

import { useEffect, useRef } from "react";
import { useMarketColors, useThemePalette } from "@/stores/themeStore";
import type { CandleData } from "./chart/types";

let echartsPromise: Promise<typeof import("echarts")> | null = null;
function loadECharts() {
  if (!echartsPromise) echartsPromise = import("echarts");
  return echartsPromise;
}

// ── 지표 계산 ────────────────────────────────────────────────
function calcRSI(data: CandleData[], period = 14): (number | null)[] {
  return data.map((_, i) => {
    if (i < period) return null;
    let gain = 0, loss = 0;
    for (let j = i - period + 1; j <= i; j++) {
      const d = data[j].close - data[j - 1].close;
      if (d > 0) gain += d; else loss -= d;
    }
    const rs = gain / (loss || 0.0001);
    return 100 - 100 / (1 + rs);
  });
}

function emaArr(values: number[], period: number): number[] {
  const k = 2 / (period + 1);
  let ema  = values[0];
  return values.map(v => { ema = v * k + ema * (1 - k); return ema; });
}

function calcMACD(data: CandleData[]) {
  const closes    = data.map(c => c.close);
  const ema12     = emaArr(closes, 12);
  const ema26     = emaArr(closes, 26);
  const macdLine  = ema12.map((v, i) => v - ema26[i]);
  const signal    = emaArr(macdLine, 9);
  const histogram = macdLine.map((v, i) => v - signal[i]);
  return { macdLine, signal, histogram };
}

interface Props {
  candles: CandleData[];
  showRSI?: boolean;
  showMACD?: boolean;
}

export default function IndicatorChart({ candles, showRSI = false, showMACD = false }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef     = useRef<import("echarts").ECharts | null>(null);
  const ct  = useMarketColors();
  const pal = useThemePalette();

  const visible = showRSI || showMACD;
  const panelCount = (showRSI ? 1 : 0) + (showMACD ? 1 : 0);
  const height = panelCount * 110;

  useEffect(() => {
    if (!containerRef.current || !visible || candles.length === 0) return;
    let disposed = false;

    loadECharts().then(echarts => {
      if (disposed || !containerRef.current) return;
      if (chartRef.current) chartRef.current.dispose();

      // 앱 테마 팔레트(드라큘라는 이전과 같은 값)·사용자 상승/하락 색
      const bg     = pal.panel;
      const grid   = pal.line;
      const text   = pal.muted;
      const dates  = candles.map(c => new Date(c.time * 1000).toISOString().slice(0, 10));

      const grids: object[]   = [];
      const xAxes: object[]   = [];
      const yAxes: object[]   = [];
      const series: object[]  = [];
      const dataZoom: object[] = [
        { type: "inside", xAxisIndex: panelCount === 2 ? [0, 1] : [0], start: 60, end: 100 },
      ];

      let gridIdx = 0;

      if (showRSI) {
        const rsi = calcRSI(candles);
        grids.push({ left: 8, right: 72, top: gridIdx === 0 ? 8 : 8 + gridIdx * 110, height: 80 });
        xAxes.push({
          type: "category", data: dates, gridIndex: gridIdx,
          axisLabel: { show: gridIdx === panelCount - 1, color: text, fontSize: 10, formatter: (v: string) => v.slice(5) },
          axisLine: { lineStyle: { color: grid } }, splitLine: { show: false },
        });
        yAxes.push({
          scale: false, gridIndex: gridIdx, min: 0, max: 100,
          position: "right",
          axisLabel: { color: text, fontSize: 10 },
          axisLine: { show: false }, splitLine: { lineStyle: { color: grid, type: "dashed", opacity: 0.4 } },
        });
        series.push(
          { name: "RSI", type: "line", xAxisIndex: gridIdx, yAxisIndex: gridIdx,
            data: rsi, smooth: false, symbol: "none",
            lineStyle: { color: pal.cyan, width: 1.5 },
            markLine: { silent: true, symbol: ["none","none"],
              data: [{ yAxis: 70, lineStyle: { color: ct.downColor, type: "dashed", opacity: 0.5 } },
                     { yAxis: 30, lineStyle: { color: ct.upColor, type: "dashed", opacity: 0.5 } }] } },
        );
        gridIdx++;
      }

      if (showMACD) {
        const { macdLine, signal, histogram } = calcMACD(candles);
        grids.push({ left: 8, right: 72, top: gridIdx === 0 ? 8 : 8 + gridIdx * 110, height: 80 });
        xAxes.push({
          type: "category", data: dates, gridIndex: gridIdx,
          axisLabel: { show: gridIdx === panelCount - 1, color: text, fontSize: 10, formatter: (v: string) => v.slice(5) },
          axisLine: { lineStyle: { color: grid } }, splitLine: { show: false },
        });
        yAxes.push({
          scale: true, gridIndex: gridIdx, position: "right",
          axisLabel: { color: text, fontSize: 9, formatter: (v: number) => v.toFixed(1) },
          axisLine: { show: false }, splitLine: { lineStyle: { color: grid, type: "dashed", opacity: 0.4 } },
        });
        series.push(
          { name: "MACD", type: "line", xAxisIndex: gridIdx, yAxisIndex: gridIdx,
            data: macdLine, symbol: "none", lineStyle: { color: pal.pink, width: 1.5 } },
          { name: "Signal", type: "line", xAxisIndex: gridIdx, yAxisIndex: gridIdx,
            data: signal, symbol: "none", lineStyle: { color: pal.yellow, width: 1.2 } },
          { name: "Histogram", type: "bar", xAxisIndex: gridIdx, yAxisIndex: gridIdx,
            barCategoryGap: "0%",
            data: histogram.map(v => ({
              value: v,
              itemStyle: { color: v >= 0 ? `${ct.upColor}99` : `${ct.downColor}99` },
            })) },
        );
      }

      const chart = echarts.init(containerRef.current, undefined, {
        renderer: "canvas", width: containerRef.current.clientWidth, height,
      });
      chartRef.current = chart;

      chart.setOption({
        backgroundColor: bg, animation: false,
        legend: { top: 4, right: 80, textStyle: { color: text, fontSize: 10 }, itemWidth: 12, itemHeight: 2 },
        tooltip: { trigger: "axis", backgroundColor: pal.inner, borderColor: grid, textStyle: { color: text, fontSize: 11 } },
        grid: grids, xAxis: xAxes, yAxis: yAxes, series, dataZoom,
      });

      const onResize = () => {
        if (containerRef.current && chartRef.current && !chartRef.current.isDisposed())
          chartRef.current.resize({ width: containerRef.current.clientWidth });
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
  }, [candles, showRSI, showMACD, visible, height, panelCount, pal, ct.upColor, ct.downColor]);

  if (!visible || candles.length === 0) return null;

  return (
    <div
      ref={containerRef}
      className="w-full rounded-lg border border-tm-line overflow-hidden"
      style={{ height }}
    />
  );
}
