"use client";

import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { Card } from "@/components/ui/Card";
import SettingsTabs from "@/components/settings/SettingsTabs";
import { useThemeStore, ChartThemeKey, CHART_THEMES } from "@/stores/themeStore";
import { useA11yStore, TEXT_SIZES, TextSize } from "@/stores/a11yStore";

function Segmented<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string }[]; onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex rounded-lg border border-gray-300 dark:border-dracula-line overflow-hidden">
      {options.map((o) => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={`px-4 py-2 text-sm font-medium transition-colors ${
            value === o.value
              ? "bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg"
              : "bg-white dark:bg-dracula-bg text-gray-700 dark:text-dracula-fg hover:bg-gray-100 dark:hover:bg-dracula-line/40"
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function Row({ label, description, children }: { label: string; description?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-3 border-b border-gray-100 dark:border-dracula-line last:border-0">
      <div>
        <p className="text-sm font-medium text-gray-900 dark:text-dracula-fg">{label}</p>
        {description && <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">{description}</p>}
      </div>
      {children}
    </div>
  );
}

export default function AppearanceSettingsPage() {
  const { theme, setTheme } = useTheme();
  const { chartTheme, setChartTheme } = useThemeStore();
  const { textSize, setTextSize } = useA11yStore();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg mb-6">화면 설정</h1>
      <SettingsTabs />

      <Card className="p-5" outerClassName="mb-6">
        <h2 className="text-sm font-semibold text-purple-600 dark:text-dracula-purple mb-1">테마</h2>
        <Row label="화면 모드" description="시스템 설정을 따르거나 직접 선택">
          {mounted && (
            <Segmented<string>
              value={theme ?? "system"}
              onChange={setTheme}
              options={[{ value: "system", label: "시스템" }, { value: "light", label: "라이트" }, { value: "dark", label: "다크" }]}
            />
          )}
        </Row>
        <Row label="차트 색상" description="상승/하락 캔들 색 구성">
          <select
            value={chartTheme}
            onChange={(e) => setChartTheme(e.target.value as ChartThemeKey)}
            className="text-sm border border-gray-300 dark:border-dracula-line rounded-md px-2 py-1.5
                       bg-white dark:bg-dracula-bg text-gray-700 dark:text-dracula-fg cursor-pointer
                       focus:outline-none focus:ring-2 focus:ring-blue-500/30 dark:focus:ring-dracula-purple/40"
          >
            {(Object.entries(CHART_THEMES) as [ChartThemeKey, { label: string }][]).map(([key, t]) => (
              <option key={key} value={key}>{t.label}</option>
            ))}
          </select>
        </Row>
      </Card>

      <Card className="p-5" outerClassName="mb-6">
        <h2 className="text-sm font-semibold text-green-600 dark:text-dracula-green mb-1">접근성</h2>
        <Row label="글자 크기" description="화면 전체 글자와 간격이 함께 커집니다">
          <Segmented<TextSize>
            value={textSize}
            onChange={setTextSize}
            options={(Object.entries(TEXT_SIZES) as [TextSize, { label: string }][]).map(([v, t]) => ({ value: v, label: t.label }))}
          />
        </Row>
        <p className="text-xs text-gray-400 dark:text-dracula-comment mt-3">미리보기: 이 문장의 크기가 선택한 글자 크기로 바뀝니다.</p>
      </Card>
    </div>
  );
}
