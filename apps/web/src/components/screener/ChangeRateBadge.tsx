"use client";

import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

interface Props { rate: number; amount: number; }

function fmt(n: number) {
  return Math.abs(n) >= 100_000_000
    ? `${(n / 100_000_000).toFixed(0)}억`
    : n.toLocaleString("ko-KR");
}

/** 등락률(위) + 전일 대비 금액(아래). 색은 사용자 차트 테마의 상승/하락색. */
export default function ChangeRateBadge({ rate, amount }: Props) {
  const theme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  const up    = rate >= 0;
  const color = up ? theme.upColor : theme.downColor;
  const sign  = up ? "+" : "";
  return (
    <div className="flex flex-col items-end gap-px">
      <span className="num text-13 font-medium" style={{ color }}>
        {sign}{rate.toFixed(2)}%
      </span>
      <span className="num text-2xs" style={{ color }}>
        {sign}{fmt(amount)}
      </span>
    </div>
  );
}
