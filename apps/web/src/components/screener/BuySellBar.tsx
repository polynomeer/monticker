"use client";

import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

interface Props { buy: number; sell: number; }

export default function BuySellBar({ buy, sell }: Props) {
  const theme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  return (
    <div className="flex w-full min-w-[80px] items-center gap-1.5">
      <div className="relative flex h-1.5 flex-1 overflow-hidden rounded-full bg-tm-inner">
        <div className="h-full rounded-l-full transition-[width] duration-300 ease-out" style={{ width: `${buy}%`, backgroundColor: theme.upColor, opacity: 0.85 }} />
        <div className="ml-auto h-full rounded-r-full transition-[width] duration-300 ease-out" style={{ width: `${sell}%`, backgroundColor: theme.downColor, opacity: 0.85 }} />
      </div>
      <div className="num flex shrink-0 gap-1 text-2xs">
        <span style={{ color: theme.upColor }}>{buy}</span>
        <span className="text-tm-muted">/</span>
        <span style={{ color: theme.downColor }}>{sell}</span>
      </div>
    </div>
  );
}
