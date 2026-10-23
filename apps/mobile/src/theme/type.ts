import type { TextStyle } from "react-native";

/** 시안의 글자 크기 단계 — iOS 기본 크기(Large Title 34, Title3 20, Body 17, Subhead 15, Footnote 13, Caption 12) */
export const font = {
  largeTitle: { fontSize: 34, fontWeight: "700", letterSpacing: 0.2 },
  title: { fontSize: 20, fontWeight: "700" },
  headline: { fontSize: 17, fontWeight: "600" },
  body: { fontSize: 17 },
  callout: { fontSize: 16, fontWeight: "600" },
  subhead: { fontSize: 15 },
  footnote: { fontSize: 13 },
  caption: { fontSize: 12 },
  section: { fontSize: 13, fontWeight: "600" },
} satisfies Record<string, TextStyle>;

/** 숫자는 자릿수가 흔들리지 않게 */
export const tabular: TextStyle = { fontVariant: ["tabular-nums"] };

export const radius = { card: 26, tile: 20, chip: 18, pill: 22 } as const;
