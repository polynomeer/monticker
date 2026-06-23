"use client";

import { useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { useThemeStore, ChartThemeKey, CHART_THEMES } from "@/stores/themeStore";

export default function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const { chartTheme, setChartTheme } = useThemeStore();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  if (!mounted) return <div className="w-20 h-7" />;

  return (
    <div className="flex items-center gap-2">
      {/* 차트 테마 선택 */}
      <select
        value={chartTheme}
        onChange={e => setChartTheme(e.target.value as ChartThemeKey)}
        className="text-xs border border-gray-300 dark:border-[#44475a] dark:border-gray-600 rounded px-2 py-1
                   bg-white dark:bg-[#282a36] text-gray-700 dark:text-[#f8f8f2]
                   focus:outline-none cursor-pointer"
        title="차트 테마"
      >
        {(Object.entries(CHART_THEMES) as [ChartThemeKey, { label: string }][]).map(([key, t]) => (
          <option key={key} value={key}>{t.label}</option>
        ))}
      </select>

      {/* 라이트/다크 토글 */}
      <button
        onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
        className="flex items-center justify-center w-8 h-8 rounded-lg
                   border border-gray-300 dark:border-gray-600
                   bg-white dark:bg-[#282a36]
                   text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700
                   transition-colors"
        title={resolvedTheme === "dark" ? "라이트 모드" : "다크 모드"}
      >
        {resolvedTheme === "dark" ? "☀️" : "🌙"}
      </button>
    </div>
  );
}
