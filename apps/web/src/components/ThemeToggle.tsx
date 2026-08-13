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

  const isDark = resolvedTheme === "dark";

  return (
    <div className="flex items-center gap-2">
      {/* 차트 테마 선택 */}
      <select
        value={chartTheme}
        onChange={e => setChartTheme(e.target.value as ChartThemeKey)}
        className="text-xs border border-gray-300 dark:border-dracula-line rounded-md px-2 py-1
                   bg-white dark:bg-dracula-bg text-gray-700 dark:text-dracula-fg
                   hover:border-gray-400 dark:hover:border-dracula-comment
                   focus:outline-none focus:ring-2 focus:ring-blue-500/30 dark:focus:ring-dracula-purple/40
                   transition-colors cursor-pointer"
        title="차트 테마"
      >
        {(Object.entries(CHART_THEMES) as [ChartThemeKey, { label: string }][]).map(([key, t]) => (
          <option key={key} value={key}>{t.label}</option>
        ))}
      </select>

      {/* 라이트/다크 토글 */}
      <button
        onClick={() => setTheme(isDark ? "light" : "dark")}
        className="flex items-center justify-center w-8 h-8 rounded-lg
                   border border-gray-300 dark:border-dracula-line
                   bg-white dark:bg-dracula-bg
                   text-gray-600 dark:text-dracula-yellow
                   hover:bg-gray-100 dark:hover:bg-dracula-line/40
                   active:scale-95 transition-all duration-150"
        aria-label={isDark ? "라이트 모드로 전환" : "다크 모드로 전환"}
        title={isDark ? "라이트 모드" : "다크 모드"}
      >
        {isDark ? (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41" />
          </svg>
        ) : (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
            <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
          </svg>
        )}
      </button>
    </div>
  );
}
