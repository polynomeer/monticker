import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

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
  classic: {
    label:     "Classic",
    upColor:   "#2563eb",
    downColor: "#f97316",
    wickUp:    "#2563eb",
    wickDown:  "#f97316",
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
    upColor:   "#e84545",   // 양봉 — 빨강
    downColor: "#4a8fd4",   // 음봉 — 파랑
    wickUp:    "#e84545",
    wickDown:  "#4a8fd4",
  },
};

interface ThemeStore {
  chartTheme: ChartThemeKey;
  setChartTheme: (key: ChartThemeKey) => void;
}

export const useThemeStore = create<ThemeStore>()(
  persist(
    (set) => ({
      chartTheme: "default",
      setChartTheme: (key) => set({ chartTheme: key }),
    }),
    {
      name: "monticker-chart-theme",
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
      skipHydration: true,   // prevent SSR/CSR mismatch
    }
  )
);
