"use client";

import { useMemo } from "react";
import { useMarketColors, useThemePalette } from "@/stores/themeStore";
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
 * 상승·하락색은 사용자 시세 색상(useMarketColors)을 따른다: 매수 = 상승색, 매도 = 하락색(호가 패널과 같다).
 */
export default function DepthChart({ bids, asks, height = 220, priceDigits = 0 }: Props) {
  const ct = useMarketColors();
  const pal = useThemePalette();
  // StockChart와 같은 패널 배경·글자·구분선(앱 테마) — 색 값이 바뀔 때만 참조가 바뀌어 차트를 다시 만들지 않는다
  const theme: ChartTheme = useMemo(() => ({
    bg: pal.panel,
    text: pal.muted,
    grid: pal.line,
    upColor: ct.upColor,
    downColor: ct.downColor,
    palette: pal,
  }), [ct.upColor, ct.downColor, pal]);

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
