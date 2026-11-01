"use client";

// 효율적 프론티어 차트 — 무작위 포트폴리오 표본 산점도, 프론티어 선, 샤프 비율 최대 지점(과거 데이터 기준),
// 분석 결과·동일가중·현재 보유 비교점. 차트 라이브러리는 어댑터 뒤에 숨긴다(페이지는 echarts를 가져오지 않는다).

import { useMemo } from "react";
import { useReducedMotion } from "@/stores/a11yStore";
import { useThemePalette } from "@/stores/themeStore";
import type { ThemePalette } from "@/lib/themes";
import EChartsFrontierAdapter from "@/components/stock/chart/EChartsFrontierAdapter";
import type { FrontierAdapterComponent, FrontierChartData, FrontierChartTheme } from "@/components/stock/chart/types";

export type { FrontierChartData, RiskReturnPoint } from "@/components/stock/chart/types";

const ActiveAdapter: FrontierAdapterComponent = EChartsFrontierAdapter;

/** 캔버스(ECharts)는 CSS 변수를 못 읽어 지금 테마의 팔레트 값을 넘긴다 */
export function frontierTheme(p: ThemePalette): FrontierChartTheme {
  return {
    bg: p.panel,
    text: p.muted,
    grid: p.line,
    sample: p.soft,
    frontier: p.accent,
    maxSharpe: p.green,
    optimal: p.accent,
    equalWeight: p.pink,
    held: p.cyan,
  };
}

export function FrontierChart({ data, height = 320 }: { data: FrontierChartData; height?: number }) {
  const reduceMotion = useReducedMotion();
  const palette = useThemePalette();
  const theme = useMemo(() => frontierTheme(palette), [palette]);
  const empty = useMemo(
    () => data.samples.length === 0 && data.frontier.length === 0 && !data.maxSharpe && !data.optimal && !data.equalWeight && !data.held,
    [data],
  );
  if (empty) {
    return (
      <div className="grid place-items-center rounded-lg border border-dashed border-tm-line2 text-center text-13 text-tm-muted" style={{ height }}>
        종목을 2개 이상 고르고 &lsquo;분석 실행&rsquo;을 누르면<br />위험-수익 분포를 그립니다.
      </div>
    );
  }
  return <ActiveAdapter data={data} theme={theme} height={height} reduceMotion={reduceMotion} />;
}
