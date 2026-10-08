"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import { useEffect } from "react";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import { useA11yStore, applyA11y } from "@/stores/a11yStore";

function StoreHydrator() {
  useEffect(() => {
    // rehydrate zustand stores from localStorage after mount
    useThemeStore.persist.rehydrate();
    Promise.resolve(useA11yStore.persist.rehydrate()).then(() => applyA11y(useA11yStore.getState()));
    // 이후 변경도 html 속성에 반영
    const unsub = useA11yStore.subscribe((st) => applyA11y(st));
    return () => unsub();
  }, []);
  return null;
}

function hexToChannels(hex: string) {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

/** 사용자 차트 테마의 상승/하락 색을 --mt-up/--mt-down에 반영 — 호가·등락률·버튼까지 같은 색을 쓴다 */
function MarketColorSync() {
  const chartTheme = useThemeStore((s) => s.chartTheme);
  useEffect(() => {
    const t = CHART_THEMES[chartTheme] ?? CHART_THEMES.default;
    const root = document.documentElement;
    root.style.setProperty("--mt-up", hexToChannels(t.upColor));
    root.style.setProperty("--mt-down", hexToChannels(t.downColor));
  }, [chartTheme]);
  return null;
}

export default function ThemeProvider({ children }: { children: React.ReactNode }) {
  return (
    <NextThemesProvider
      attribute="class"
      // 터미널 디자인(ADR-066)은 다크 전용 — 라이트/시스템 테마는 docs/design-rollout-plan.md 후속 항목
      forcedTheme="dark"
      defaultTheme="dark"
      disableTransitionOnChange
    >
      <StoreHydrator />
      <MarketColorSync />
      {children}
    </NextThemesProvider>
  );
}
