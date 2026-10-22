"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import { useEffect } from "react";
import { useThemeStore, resolveMarketColors } from "@/stores/themeStore";
import { DEFAULT_APP_THEME, hexToChannels, isAppThemeKey } from "@/lib/themes";
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

/**
 * 사용자 시세 색상의 상승/하락 색을 --mt-up/--mt-down에 반영 — 호가·등락률·버튼까지 같은 색을 쓴다.
 * '글로벌'(default)이면 앱 테마의 기본 색(라이트 테마는 상승 빨강·하락 파랑, 드라큘라는 이전과 같은 초록·빨강).
 */
export function MarketColorSync() {
  const chartTheme = useThemeStore((s) => s.chartTheme);
  const appTheme = useThemeStore((s) => s.appTheme);
  useEffect(() => {
    const t = resolveMarketColors(chartTheme, appTheme);
    const root = document.documentElement;
    root.style.setProperty("--mt-up", hexToChannels(t.upColor));
    root.style.setProperty("--mt-down", hexToChannels(t.downColor));
  }, [chartTheme, appTheme]);
  return null;
}

/**
 * 앱 테마 → html[data-theme] — globals.css가 --tm-* 변수를 덮는다. 첫 페인트 전 값은 ThemeScript(layout)가 달고,
 * 여기서는 하이드레이션 뒤와 설정 변경을 맞춘다. 라이트 테마의 color-scheme은 globals.css가 data-theme으로 정한다.
 */
export function AppThemeSync() {
  const appTheme = useThemeStore((s) => s.appTheme);
  useEffect(() => {
    const key = isAppThemeKey(appTheme) ? appTheme : DEFAULT_APP_THEME;
    document.documentElement.dataset.theme = key;
  }, [appTheme]);
  return null;
}

export default function ThemeProvider({ children }: { children: React.ReactNode }) {
  return (
    <NextThemesProvider
      attribute="class"
      // html.dark는 라이트 테마에서도 그대로 둔다 — 색은 전부 테마 변수(--tm-*)로 바뀌고, 남은 dark: 변형은 한 곳
      // (EChartsAdapter dark:border-dracula-line)뿐이라 .dark를 떼면 오히려 옛 라이트 색(gray-200 등)으로 돌아간다.
      // 라이트·다크 구분은 html[data-theme]으로 하고, color-scheme은 globals.css가 정한다.
      forcedTheme="dark"
      defaultTheme="dark"
      // next-themes가 html에 style="color-scheme: dark"를 인라인으로 박으면 라이트 테마의 CSS color-scheme을 덮는다
      enableColorScheme={false}
      disableTransitionOnChange
    >
      <StoreHydrator />
      <AppThemeSync />
      <MarketColorSync />
      {children}
    </NextThemesProvider>
  );
}
