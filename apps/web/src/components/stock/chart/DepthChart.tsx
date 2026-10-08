"use client";

import { useMemo } from "react";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import type { ChartTheme, DepthAdapterComponent, DepthLevel } from "./types";
import { cumulativeDepth } from "./depth";
import EChartsDepthAdapter from "./EChartsDepthAdapter";

const ActiveAdapter: DepthAdapterComponent = EChartsDepthAdapter;

interface Props {
  bids: DepthLevel[];
  asks: DepthLevel[];
  height?: number;
  priceDigits?: number;
}

/**
 * 누적 호가 깊이 차트 — 차트 라이브러리는 어댑터 뒤에 숨긴다(페이지는 echarts를 직접 가져오지 않는다).
 * 상승·하락색은 사용자가 고른 차트 테마(CHART_THEMES)를 따른다: 매수 = 상승색, 매도 = 하락색(호가 패널과 같다).
 */
export default function DepthChart({ bids, asks, height = 220, priceDigits = 0 }: Props) {
  const { chartTheme } = useThemeStore();
  const ct = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;
  // StockChart와 같은 터미널 배경·글자·구분선 — 색 값이 바뀔 때만 참조가 바뀌어 차트를 다시 만들지 않는다
  const theme: ChartTheme = useMemo(() => ({
    bg: "#282a36",
    text: "#a4abcf",
    grid: "#34364a",
    upColor: ct.upColor,
    downColor: ct.downColor,
  }), [ct.upColor, ct.downColor]);

  const depth = useMemo(() => cumulativeDepth(bids, asks), [bids, asks]);

  if (depth.bids.length === 0 && depth.asks.length === 0) {
    return (
      <div className="flex items-center justify-center rounded-lg bg-tm-inner text-13 text-tm-muted" style={{ height }}>
        호가 데이터가 없습니다.
      </div>
    );
  }
  return <ActiveAdapter depth={depth} height={height} theme={theme} priceDigits={priceDigits} />;
}
