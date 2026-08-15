"use client";

import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

interface Props { buy: number; sell: number; }

export default function BuySellBar({ buy, sell }: Props) {
  const theme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  return (
    <div className="flex items-center gap-1 w-full min-w-[80px]">
      <div className="relative flex h-2 flex-1 rounded-full overflow-hidden bg-dracula-line">
        <div
          className="h-full rounded-l-full transition-[width] duration-300 ease-out"
          style={{ width: `${buy}%`, backgroundColor: theme.upColor, opacity: 0.8 }}
        />
        <div
          className="h-full rounded-r-full ml-auto transition-[width] duration-300 ease-out"
          style={{ width: `${sell}%`, backgroundColor: theme.downColor, opacity: 0.8 }}
        />
      </div>
      <div className="flex gap-1 text-[10px] tabular-nums shrink-0">
        <span style={{ color: theme.upColor }}>{buy}</span>
        <span className="text-dracula-line">/</span>
        <span style={{ color: theme.downColor }}>{sell}</span>
      </div>
    </div>
  );
}
