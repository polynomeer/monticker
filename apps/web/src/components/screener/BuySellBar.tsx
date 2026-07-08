"use client";

import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";

interface Props { buy: number; sell: number; }

export default function BuySellBar({ buy, sell }: Props) {
  const theme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  return (
    <div className="flex items-center gap-1 w-full min-w-[80px]">
      <div className="relative flex h-2 flex-1 rounded-full overflow-hidden bg-[#44475a]">
        <div
          className="h-full rounded-l-full"
          style={{ width: `${buy}%`, backgroundColor: theme.upColor, opacity: 0.8 }}
        />
        <div
          className="h-full rounded-r-full ml-auto"
          style={{ width: `${sell}%`, backgroundColor: theme.downColor, opacity: 0.8 }}
        />
      </div>
      <div className="flex gap-1 text-[10px] shrink-0">
        <span style={{ color: theme.upColor }}>{buy}</span>
        <span className="text-[#44475a]">/</span>
        <span style={{ color: theme.downColor }}>{sell}</span>
      </div>
    </div>
  );
}
