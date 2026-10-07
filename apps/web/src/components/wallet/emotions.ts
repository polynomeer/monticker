/** 백엔드 EmotionType(order_emotion_tags.emotion)과 1:1 — 화면 문구만 여기서 정한다. */
export const EMOTIONS = [
  { value: "CONFIDENT", label: "확신" },
  { value: "PLANNED", label: "계획대로" },
  { value: "LONG_TERM", label: "장기 투자" },
  { value: "NEWS_BASED", label: "뉴스 보고" },
  { value: "REBALANCING", label: "비중 조절" },
  { value: "ANXIOUS", label: "불안" },
  { value: "FOMO", label: "FOMO" },
  { value: "IMPATIENT", label: "조급함" },
  { value: "FOLLOWING", label: "따라삼" },
  { value: "AVERAGING_DOWN", label: "물타기" },
  { value: "INTUITION", label: "직감" },
  { value: "OTHER", label: "기타" },
] as const;

export type EmotionValue = (typeof EMOTIONS)[number]["value"];

export function emotionLabel(v: string | null | undefined) {
  if (!v) return null;
  return EMOTIONS.find((e) => e.value === v)?.label ?? v;
}

export interface EmotionTag {
  id: number;
  paperTradeId: number;
  emotion: string;
  memo: string | null;
  createdAt: string;
}
