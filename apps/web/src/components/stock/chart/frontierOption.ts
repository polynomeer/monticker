// 위험-수익 산점도의 ECharts 옵션 — 순수 함수라 echarts 없이 테스트한다.
import type { FrontierChartData, FrontierChartTheme, RiskReturnPoint } from "./types";

export const FRONTIER_SERIES = {
  samples: "무작위 포트폴리오",
  frontier: "효율적 프론티어",
  maxSharpe: "샤프 비율 최대 지점 (과거 데이터 기준)",
  optimal: "분석 결과 비중",
  equalWeight: "동일가중",
  held: "현재 보유 비중",
} as const;

type Datum = [number, number, number | null];

function withAlpha(hex: string, alpha: number) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

const pt = (p: RiskReturnPoint, sharpe: number | null = null): Datum => [p.risk, p.ret, sharpe];

/** 툴팁 한 줄 — 위험·수익(%)과, 있으면 샤프 */
export function frontierTooltip(seriesName: string, value: Datum) {
  const [risk, ret, sharpe] = value;
  const parts = [`${seriesName}`, `예상 위험 ${risk.toFixed(2)}%`, `기대 수익 ${ret.toFixed(2)}%`];
  if (sharpe != null) parts.push(`샤프 ${sharpe.toFixed(2)}`);
  return parts.join("<br/>");
}

export function buildFrontierOption(data: FrontierChartData, theme: FrontierChartTheme, reduceMotion = false) {
  const frontier = [...data.frontier].sort((a, b) => a.risk - b.risk);
  const marker = (name: string, p: RiskReturnPoint | undefined, symbol: string, size: number, color: string, hollow = false, sharpe: number | null = null) => ({
    id: name,
    name,
    type: "scatter" as const,
    symbol,
    symbolSize: size,
    z: 5,
    itemStyle: hollow
      ? { color: "transparent", borderColor: color, borderWidth: 2 }
      : { color, borderColor: theme.bg, borderWidth: 2 },
    data: p ? [pt(p, sharpe)] : [],
  });

  const series = [
    {
      id: FRONTIER_SERIES.samples,
      name: FRONTIER_SERIES.samples,
      type: "scatter" as const,
      symbolSize: 4,
      z: 1,
      itemStyle: { color: withAlpha(theme.sample, 0.35) },
      emphasis: { scale: 1.8 },
      data: data.samples.map((s) => pt(s, s.sharpe)),
    },
    {
      id: FRONTIER_SERIES.frontier,
      name: FRONTIER_SERIES.frontier,
      type: "line" as const,
      symbol: "circle",
      symbolSize: 5,
      z: 3,
      lineStyle: { color: theme.frontier, width: 2.4 },
      itemStyle: { color: theme.frontier },
      data: frontier.map((p) => pt(p)),
    },
    marker(FRONTIER_SERIES.maxSharpe, data.maxSharpe, "diamond", 18, theme.maxSharpe, false, data.maxSharpe?.sharpe ?? null),
    marker(FRONTIER_SERIES.optimal, data.optimal, "circle", 12, theme.optimal),
    marker(FRONTIER_SERIES.equalWeight, data.equalWeight, "circle", 12, theme.equalWeight, true),
    marker(FRONTIER_SERIES.held, data.held, "rect", 11, theme.held),
  ];

  const legendNames = series.filter((s) => s.data.length > 0).map((s) => s.name);

  return {
    backgroundColor: "transparent",
    animation: !reduceMotion,
    grid: { left: 8, right: 16, top: 56, bottom: 28, containLabel: true },
    legend: {
      data: legendNames,
      top: 0,
      left: 0,
      itemWidth: 12,
      itemHeight: 10,
      textStyle: { color: theme.text, fontSize: 11 },
      inactiveColor: theme.grid,
    },
    tooltip: {
      trigger: "item" as const,
      backgroundColor: theme.bg,
      borderColor: theme.grid,
      textStyle: { color: theme.text, fontSize: 12 },
      transitionDuration: reduceMotion ? 0 : 0.2,
      formatter: (p: { seriesName: string; value: Datum }) => frontierTooltip(p.seriesName, p.value),
    },
    xAxis: {
      type: "value" as const,
      scale: true,
      name: "예상 위험 (연 변동성, %)",
      nameLocation: "middle" as const,
      nameGap: 26,
      nameTextStyle: { color: theme.text, fontSize: 11 },
      axisLine: { lineStyle: { color: theme.grid } },
      axisLabel: { color: theme.text, fontSize: 10, formatter: (v: number) => `${v.toFixed(1)}%` },
      splitLine: { lineStyle: { color: theme.grid, type: "dashed" as const } },
    },
    yAxis: {
      type: "value" as const,
      scale: true,
      name: "기대 수익률 (연, %)",
      nameTextStyle: { color: theme.text, fontSize: 11, align: "left" as const },
      axisLine: { lineStyle: { color: theme.grid } },
      axisLabel: { color: theme.text, fontSize: 10, formatter: (v: number) => `${v.toFixed(1)}%` },
      splitLine: { lineStyle: { color: theme.grid, type: "dashed" as const } },
    },
    series,
  };
}
