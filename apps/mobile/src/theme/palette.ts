/**
 * ADR-113 — 앱 색 토큰. 값은 iOS 디자인 시안(다크 = 드라큘라, 라이트)과 같다.
 * 화면 코드는 hex를 직접 쓰지 않고 `useTheme().c.*`만 쓴다.
 *
 * 색 역할(웹 ADR-112와 같음): live 주황 = 실전(실제 돈) 전용, caution 노랑 = 모의·주의, disclosure 청록 = 공시,
 * danger 빨강 = 위험 경고·로그아웃(상승·하락 색 설정과 무관), accent 보라 = 선택·주요 버튼.
 */

export type ColorScheme = "dark" | "light";
export type SchemePreference = ColorScheme | "system";
export type UpDownPreference = "auto" | "redUp" | "greenUp";

export interface Palette {
  bg: string;
  card: string;
  /** 칩·입력 바탕 */
  fill: string;
  /** 아바타·눌린 카드 */
  raised: string;
  /** 분할 버튼 선택 칸 */
  segmentOn: string;
  line: string;
  text: string;
  body: string;
  soft: string;
  muted: string;
  chevron: string;
  accent: string;
  accentPressed: string;
  onAccent: string;
  /** 떠 있는 유리 버튼·탭 바 */
  glass: string;
  glassBorder: string;
  glassActive: string;
  positive: string;
  caution: string;
  cautionBg: string;
  live: string;
  liveBg: string;
  liveBorder: string;
  disclosure: string;
  danger: string;
  switchOn: string;
  switchOff: string;
  shadow: string;
}

export const dracula: Palette = {
  bg: "#16171e",
  card: "#24262f",
  fill: "#2a2c38",
  raised: "#343746",
  segmentOn: "#3e4154",
  line: "#3a3d4e",
  text: "#f8f8f2",
  body: "#e2e5f5",
  soft: "#c3c8e2",
  muted: "#a4abcf",
  chevron: "#a4abcf",
  accent: "#bd93f9",
  accentPressed: "#d6bcfa",
  onAccent: "#16171e",
  glass: "rgba(40, 42, 54, 0.62)",
  glassBorder: "rgba(255, 255, 255, 0.10)",
  glassActive: "rgba(255, 255, 255, 0.12)",
  positive: "#50fa7b",
  caution: "#f1fa8c",
  cautionBg: "rgba(241, 250, 140, 0.14)",
  live: "#ffb86c",
  liveBg: "rgba(255, 184, 108, 0.18)",
  liveBorder: "rgba(255, 184, 108, 0.55)",
  disclosure: "#8be9fd",
  danger: "#ff7b7b",
  switchOn: "#30b35c",
  switchOff: "#3a3d4e",
  shadow: "#000000",
};

export const light: Palette = {
  bg: "#f2f2f7",
  card: "#ffffff",
  fill: "#e5e5ea",
  raised: "#e9e9ef",
  segmentOn: "#ffffff",
  line: "#d1d1d6",
  text: "#1c1c1e",
  body: "#3c3c43",
  soft: "#3c3c43",
  muted: "#6b6e80",
  chevron: "#8e8e93",
  accent: "#7c4dd6",
  accentPressed: "#5b33ad",
  onAccent: "#ffffff",
  glass: "rgba(255, 255, 255, 0.68)",
  glassBorder: "rgba(0, 0, 0, 0.06)",
  glassActive: "rgba(0, 0, 0, 0.06)",
  positive: "#18803f",
  caution: "#7a5c00",
  cautionBg: "#fff3c4",
  live: "#a04e00",
  liveBg: "rgba(230, 120, 0, 0.14)",
  liveBorder: "rgba(196, 96, 0, 0.55)",
  disclosure: "#0b7a90",
  danger: "#d0263c",
  switchOn: "#34c759",
  switchOff: "#e3e3e8",
  shadow: "#000000",
};

export const palettes: Record<ColorScheme, Palette> = { dark: dracula, light };

export interface MarketColors {
  up: string;
  down: string;
  /** 상승·하락 색 알약·버튼 위 글자 */
  onUp: string;
  onDown: string;
}

const MARKET: Record<ColorScheme, Record<"redUp" | "greenUp", MarketColors>> = {
  dark: {
    greenUp: { up: "#0ecb81", down: "#f6465d", onUp: "#16171e", onDown: "#16171e" },
    redUp: { up: "#f6465d", down: "#4c8dff", onUp: "#16171e", onDown: "#16171e" },
  },
  light: {
    redUp: { up: "#d0263c", down: "#1b64da", onUp: "#ffffff", onDown: "#ffffff" },
    greenUp: { up: "#08804f", down: "#d0263c", onUp: "#ffffff", onDown: "#ffffff" },
  },
};

/** 화면 모드에 맞춤: 다크는 초록 상승, 라이트는 빨강 상승 */
export function resolveMarketColors(scheme: ColorScheme, pref: UpDownPreference): MarketColors {
  const style = pref === "auto" ? (scheme === "dark" ? "greenUp" : "redUp") : pref;
  return MARKET[scheme][style];
}

/** 시스템 따라가기일 때만 기기 설정을 본다. 기기 값을 모르면 드라큘라 다크 */
export function resolveScheme(pref: SchemePreference, system: string | null | undefined): ColorScheme {
  if (pref === "system") return system === "light" ? "light" : "dark";
  return pref;
}

/** 등락 방향 → 색. 0은 보조 글자색 */
export function changeColor(value: number, market: MarketColors, neutral: string): string {
  if (value > 0) return market.up;
  if (value < 0) return market.down;
  return neutral;
}
