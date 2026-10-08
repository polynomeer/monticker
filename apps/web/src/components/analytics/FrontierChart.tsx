"use client";

// 효율적 프론티어 차트 — 무작위 포트폴리오 표본 산점도, 프론티어 선, 샤프 비율 최대 지점(과거 데이터 기준),
// 분석 결과·동일가중·현재 보유 비교점. 차트 라이브러리는 어댑터 뒤에 숨긴다(페이지는 echarts를 가져오지 않는다).

import { useMemo } from "react";
import { useReducedMotion } from "@/stores/a11yStore";
import { dracula, tm } from "@/lib/designTokens";
import EChartsFrontierAdapter from "@/components/stock/chart/EChartsFrontierAdapter";
import type { FrontierAdapterComponent, FrontierChartData, FrontierChartTheme } from "@/components/stock/chart/types";

export type { FrontierChartData, RiskReturnPoint } from "@/components/stock/chart/types";

const ActiveAdapter: FrontierAdapterComponent = EChartsFrontierAdapter;

const THEME: FrontierChartTheme = {
  bg: tm.panel,
  text: tm.muted,
  grid: tm.line,
  sample: tm.soft,
  frontier: dracula.purple,
  maxSharpe: dracula.green,
  optimal: dracula.purple,
  equalWeight: dracula.orange,
  held: dracula.cyan,
};

export function FrontierChart({ data, height = 320 }: { data: FrontierChartData; height?: number }) {
  const reduceMotion = useReducedMotion();
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
  return <ActiveAdapter data={data} theme={THEME} height={height} reduceMotion={reduceMotion} />;
}
