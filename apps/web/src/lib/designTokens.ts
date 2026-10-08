// 색 토큰의 단일 출처 — tailwind.config.ts가 이 값으로 `dracula-*`·`tm-*` 클래스를 만들고,
// 캔버스 차트(ECharts 어댑터)처럼 CSS 클래스를 못 쓰는 곳은 여기서 직접 읽는다.
// 상승/하락 색은 사용자 차트 테마(themeStore)라 여기 두지 않는다.

export const dracula = {
  bg: "#282a36",
  surface: "#21222c",
  line: "#44475a",
  comment: "#6272a4",
  fg: "#f8f8f2",
  purple: "#bd93f9",
  pink: "#ff79c6",
  green: "#50fa7b",
  cyan: "#8be9fd",
  orange: "#ffb86c",
  red: "#ff5555",
  yellow: "#f1fa8c",
} as const;

/** 터미널 디자인 시안(ADR-066) 표면 토큰 — 페이지 < 패널 < 인셋/강조 순서로 밝아진다 */
export const tm = {
  page: "#1b1c24",
  panel: "#282a36",
  inner: "#21222c",
  raised: "#343746",
  line: "#34364a",
  line2: "#44475a",
  soft: "#c3c8e2",
  muted: "#a4abcf",
} as const;
