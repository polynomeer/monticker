import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";
import { DEFAULT_APP_THEME, THEMES, isAppThemeKey, type AppThemeKey, type ThemePalette } from "@/lib/themes";

export type ChartThemeKey = "default" | "classic" | "mono" | "korean";

export interface ChartThemeConfig {
  label: string;
  upColor: string;
  downColor: string;
  wickUp: string;
  wickDown: string;
}

export const CHART_THEMES: Record<ChartThemeKey, ChartThemeConfig> = {
  default: {
    label:     "Default",
    upColor:   "#0ecb81",
    downColor: "#f6465d",
    wickUp:    "#0ecb81",
    wickDown:  "#f6465d",
  },
  // 색약 친화 — 하락은 자홍(ADR-112: 주황은 실전 전용). 패널(#282a36) 위 글자 대비 5.8:1
  classic: {
    label:     "Classic",
    upColor:   "#2563eb",
    downColor: "#e879f9",
    wickUp:    "#2563eb",
    wickDown:  "#e879f9",
  },
  mono: {
    label:     "Mono",
    upColor:   "#e2e8f0",
    downColor: "#475569",
    wickUp:    "#e2e8f0",
    wickDown:  "#475569",
  },
  korean: {
    label:     "Korean",
    upColor:   "#ff5050",   // 양봉 — 빨강
    downColor: "#0078ff",   // 음봉 — 파랑
    wickUp:    "#ff5050",
    wickDown:  "#0078ff",
  },
};

/** 테마별 '글로벌'(default) 시세 색 — 드라큘라는 CHART_THEMES.default 그대로. 셀렉터가 같은 참조를 돌려주도록 미리 만든다 */
const THEME_DEFAULT_MARKET: Record<AppThemeKey, ChartThemeConfig> = Object.fromEntries(
  (Object.keys(THEMES) as AppThemeKey[]).map((k) => {
    const { up, down } = THEMES[k].palette;
    const same = up === CHART_THEMES.default.upColor && down === CHART_THEMES.default.downColor;
    return [k, same ? CHART_THEMES.default : { label: CHART_THEMES.default.label, upColor: up, downColor: down, wickUp: up, wickDown: down }];
  }),
) as Record<AppThemeKey, ChartThemeConfig>;

/**
 * 실제로 쓸 상승·하락 색 — 사용자가 '글로벌'(default)을 고르면 앱 테마의 기본값(라이트 테마는 상승 빨강·하락 파랑),
 * 다른 시세 색상을 고르면 테마와 무관하게 그 색.
 */
export function resolveMarketColors(chartTheme: ChartThemeKey, appTheme: AppThemeKey): ChartThemeConfig {
  if (chartTheme !== "default" && CHART_THEMES[chartTheme]) return CHART_THEMES[chartTheme];
  return THEME_DEFAULT_MARKET[appTheme] ?? CHART_THEMES.default;
}

interface ThemeStore {
  chartTheme: ChartThemeKey;
  setChartTheme: (key: ChartThemeKey) => void;
  /** 앱 테마(globals.css html[data-theme]) — 기본 드라큘라 */
  appTheme: AppThemeKey;
  setAppTheme: (key: AppThemeKey) => void;
}

/** localStorage 키 — layout.tsx의 첫 페인트 전 스크립트도 이 키에서 appTheme을 읽는다 */
export const THEME_STORAGE_KEY = "monticker-chart-theme";

export const useThemeStore = create<ThemeStore>()(
  persist(
    (set) => ({
      chartTheme: "default",
      setChartTheme: (key) => set({ chartTheme: key }),
      appTheme: DEFAULT_APP_THEME,
      setAppTheme: (key) => set({ appTheme: isAppThemeKey(key) ? key : DEFAULT_APP_THEME }),
    }),
    {
      name: THEME_STORAGE_KEY,
      storage: createJSONStorage(() => {
        // SSR safe: return a no-op storage on server
        if (typeof window === "undefined") {
          return {
            getItem: () => null,
            setItem: () => {},
            removeItem: () => {},
          };
        }
        return localStorage;
      }),
      // 저장값이 깨졌거나 없어진 테마면 기본으로
      merge: (persisted, current) => {
        const p = (persisted ?? {}) as Partial<ThemeStore>;
        return {
          ...current,
          ...p,
          appTheme: isAppThemeKey(p.appTheme) ? p.appTheme : current.appTheme,
          chartTheme: p.chartTheme && p.chartTheme in CHART_THEMES ? p.chartTheme : current.chartTheme,
        };
      },
      skipHydration: true,   // prevent SSR/CSR mismatch
    }
  )
);

/** 지금 상승·하락 색(시세 색상 × 앱 테마) — 차트·배지는 CHART_THEMES 대신 이것을 쓴다 */
export function useMarketColors(): ChartThemeConfig {
  return useThemeStore((s) => resolveMarketColors(s.chartTheme, s.appTheme));
}

/** 지금 앱 테마 팔레트(hex) — CSS 변수를 못 읽는 캔버스 차트용. 테마가 바뀌면 다시 렌더된다 */
export function useThemePalette(): ThemePalette {
  return useThemeStore((s) => (THEMES[s.appTheme] ?? THEMES[DEFAULT_APP_THEME]).palette);
}
