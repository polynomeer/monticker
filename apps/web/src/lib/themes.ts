// 앱 테마(ADR-066 터미널 디자인 + 웹 디자인 v2 W3) — 색 값은 CSS 변수(RGB 채널)로 globals.css에 있다.
//
// 값이 두 곳에 있는 이유: Tailwind 클래스(`bg-tm-panel`)는 CSS 변수를 읽어 테마가 바뀌면 다시 그릴 필요가 없고,
// 캔버스 차트(ECharts)는 CSS 변수를 못 읽어 같은 값을 JS로 받는다. 빌드 때 CSS를 생성하는 대신
// globals.css를 손으로 두고 themes.test.ts가 이 파일과 한 칸씩 맞는지 검사한다 — 가장 단순하고, 어긋나면 CI가 막는다.
// 드라큘라(기본) 값은 designTokens.ts에서 가져온다 — 기본 테마는 이전과 같은 hex다.
import { dracula, tm } from "./designTokens";

export type AppThemeKey = "dracula" | "graphite" | "navy" | "light" | "paper" | "contrast";

/** 테마 역할 색 — 이름은 CSS 변수 `--tm-<kebab>`와 같다(onAccent → --tm-on-accent) */
export interface ThemePalette {
  page: string;
  inner: string;
  panel: string;
  raised: string;
  line: string;
  line2: string;
  fg: string;
  soft: string;
  muted: string;
  comment: string;
  accent: string;
  onAccent: string;
  cyan: string;
  green: string;
  pink: string;
  yellow: string;
  orange: string;
  red: string;
  /** 오류·실패 글자(입력 오류, 요청 실패) — 상승·하락 색 설정과 무관 */
  danger: string;
  /** 옅은 강조색 — 강조 링크 호버·강조 배경 위 글자 */
  accentSoft: string;
  /** 이 테마의 기본 상승·하락 색 — 사용자 시세 색상이 '글로벌'(default)일 때 */
  up: string;
  down: string;
}

export type ThemeColorToken = keyof ThemePalette;

export interface AppTheme {
  name: string;
  description: string;
  /** 라이트 테마 — color-scheme: light(스크롤바·폼 컨트롤) */
  light: boolean;
  palette: ThemePalette;
}

export const DEFAULT_APP_THEME: AppThemeKey = "dracula";

export const THEMES: Record<AppThemeKey, AppTheme> = {
  dracula: {
    name: "드라큘라",
    description: "보라 강조, 회청색 패널. 지금 기본",
    light: false,
    palette: {
      page: tm.page, inner: tm.inner, panel: tm.panel, raised: tm.raised, line: tm.line, line2: tm.line2,
      fg: dracula.fg, soft: tm.soft, muted: tm.muted, comment: dracula.comment,
      accent: dracula.purple, onAccent: tm.page,
      cyan: dracula.cyan, green: dracula.green, pink: dracula.pink, yellow: dracula.yellow, orange: dracula.orange, red: dracula.red,
      danger: "#ff8a8a", accentSoft: "#d6bcfb",
      up: "#0ecb81", down: "#f6465d",
    },
  },
  graphite: {
    name: "그래파이트",
    description: "무채색 다크 + 파랑 강조. 숫자와 차트가 가장 또렷",
    light: false,
    palette: {
      page: "#0f1115", inner: "#0b0d10", panel: "#171a20", raised: "#232733", line: "#262a33", line2: "#363c48",
      fg: "#e8eaed", soft: "#c4c9d2", muted: "#8f96a3", comment: "#6f7683",
      accent: "#5b8cff", onAccent: "#ffffff",
      cyan: "#38bdf8", green: "#22c55e", pink: "#f472b6", yellow: "#facc15", orange: "#f59e0b", red: "#ef4444",
      danger: "#f87171", accentSoft: "#93b4ff",
      up: "#16c784", down: "#ea3943",
    },
  },
  navy: {
    name: "미드나이트 네이비",
    description: "남색 + 청록 강조. 차분하고 오래 봐도 덜 피곤",
    light: false,
    palette: {
      page: "#0b1426", inner: "#08101f", panel: "#111d33", raised: "#1a2a47", line: "#1d2c47", line2: "#2b3e63",
      fg: "#eef3ff", soft: "#c5d0e6", muted: "#8d9bb8", comment: "#6c7a98",
      accent: "#2dd4bf", onAccent: "#062421",
      cyan: "#7dd3fc", green: "#4ade80", pink: "#f9a8d4", yellow: "#fde047", orange: "#fb923c", red: "#f2545b",
      danger: "#f98a8f", accentSoft: "#7ee8d6",
      up: "#22c993", down: "#f2545b",
    },
  },
  light: {
    name: "클린 라이트",
    description: "흰 패널 + 진보라 강조. 밝고 친숙, 상승 빨강·하락 파랑",
    light: true,
    palette: {
      page: "#f5f6f8", inner: "#eef0f3", panel: "#ffffff", raised: "#e9ecf2", line: "#e3e6ec", line2: "#cfd4dd",
      fg: "#191c22", soft: "#3d4350", muted: "#646b78", comment: "#8f96a3",
      accent: "#6d4bd8", onAccent: "#ffffff",
      cyan: "#0e7490", green: "#15803d", pink: "#be185d", yellow: "#8a6a00", orange: "#c2410c", red: "#c81e3a",
      danger: "#c81e3a", accentSoft: "#5b33ad",
      up: "#d0263c", down: "#1b64da",
    },
  },
  paper: {
    name: "웜 페이퍼",
    description: "아이보리 + 남보라 강조. 긴 설명 읽기에 좋음",
    light: true,
    palette: {
      page: "#f6f1e7", inner: "#efe8da", panel: "#fffcf5", raised: "#ece4d3", line: "#e4dccb", line2: "#cfc4ae",
      fg: "#2b2620", soft: "#4a4237", muted: "#73685a", comment: "#9a8f7c",
      accent: "#5b4bc4", onAccent: "#ffffff",
      cyan: "#0f6e7d", green: "#2f7d32", pink: "#a8326e", yellow: "#7d6000", orange: "#b4530a", red: "#b3261e",
      danger: "#b3261e", accentSoft: "#4a3ba8",
      up: "#c62f3d", down: "#2160c4",
    },
  },
  contrast: {
    name: "하이 콘트라스트",
    description: "순검정 + 밝은 글자. 저시력·실외용",
    light: false,
    palette: {
      page: "#000000", inner: "#000000", panel: "#0b0b0b", raised: "#1f1f1f", line: "#3a3a3a", line2: "#707070",
      fg: "#ffffff", soft: "#f2f2f2", muted: "#cccccc", comment: "#a8a8a8",
      accent: "#c9a7ff", onAccent: "#000000",
      cyan: "#7ee8ff", green: "#6bff95", pink: "#ff8ccf", yellow: "#fff36b", orange: "#ffa94d", red: "#ff6b7d",
      danger: "#ff8c99", accentSoft: "#e2d0ff",
      up: "#33e29b", down: "#ff6b7d",
    },
  },
};

export const APP_THEME_KEYS = Object.keys(THEMES) as AppThemeKey[];

export function isAppThemeKey(v: unknown): v is AppThemeKey {
  return typeof v === "string" && v in THEMES;
}

/** CSS 변수 이름 — onAccent → --tm-on-accent. 상승·하락은 기존 --mt-up/--mt-down(MarketColorSync가 덮어쓴다) */
export function themeVarName(token: ThemeColorToken): string {
  if (token === "up" || token === "down") return `--mt-${token}`;
  return `--tm-${token.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
}

/** "#282a36" → "40 42 54" (rgb(var(--x)) / 투명도 수식어용 채널) */
export function hexToChannels(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

/** Tailwind 색 값 — 변수 채널을 rgb()로 감싼다. <alpha-value>는 쓰지 않는다(tailwind.config.ts 상승/하락 주석 참고) */
export function themeColorVar(token: ThemeColorToken): string {
  return `rgb(var(${themeVarName(token)}))`;
}

/**
 * CSS 변수에서 현재 테마 색을 hex/rgb 문자열로 읽는다 — SSR·테스트(jsdom엔 globals.css가 없다)에선 드라큘라 값.
 * React 컴포넌트는 useThemePalette()를 쓴다: 자식 effect가 ThemeProvider effect(data-theme 갱신)보다 먼저 돌아
 * 테마를 바꾼 직후엔 계산된 스타일이 이전 값일 수 있다. 이 함수는 React 밖(일회성 렌더 등)에서만 쓴다.
 */
export function readThemeColor(token: ThemeColorToken): string {
  const fallback = THEMES.dracula.palette[token];
  if (typeof window === "undefined" || typeof getComputedStyle !== "function") return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(themeVarName(token)).trim();
  if (!/^\d+ \d+ \d+$/.test(v)) return fallback;
  return `rgb(${v.replace(/ /g, ", ")})`;
}
