import { create } from "zustand";
import { persist } from "zustand/middleware";

export type ChartThemeKey = "default" | "classic" | "mono";

export interface ChartThemeConfig {
  label: string;
  upColor: string;
  downColor: string;
  wickUp: string;
  wickDown: string;
  bg: string;
  textColor: string;
  gridColor: string;
}

export const CHART_THEMES: Record<ChartThemeKey, ChartThemeConfig> = {
  default: {
    label: "Default",
    upColor:   "#26a69a",
    downColor: "#ef5350",
    wickUp:    "#26a69a",
    wickDown:  "#ef5350",
    bg:        "transparent",
    textColor: "#d1d4dc",
    gridColor: "#2a2e39",
  },
  classic: {
    label: "Classic",
    upColor:   "#2563eb",
    downColor: "#f97316",
    wickUp:    "#2563eb",
    wickDown:  "#f97316",
    bg:        "transparent",
    textColor: "#d1d4dc",
    gridColor: "#2a2e39",
  },
  mono: {
    label: "Mono",
    upColor:   "#e2e8f0",
    downColor: "#475569",
    wickUp:    "#e2e8f0",
    wickDown:  "#475569",
    bg:        "transparent",
    textColor: "#d1d4dc",
    gridColor: "#2a2e39",
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
    { name: "monticker-chart-theme" }
  )
);
