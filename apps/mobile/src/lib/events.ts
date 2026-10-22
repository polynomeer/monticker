import type { BadgeTone } from "@/components/ui/Market";

/** 서버 EventType → 화면 라벨·색 역할. 공시는 청록, 뉴스는 회색(ADR-112·113 — 주황은 실전 전용) */
export const EVENT_LABEL: Record<string, string> = {
  PRICE_SPIKE: "급등",
  PRICE_DROP: "급락",
  VOLUME_SURGE: "거래량",
  NEWS_PUBLISHED: "뉴스",
  DISCLOSURE_PUBLISHED: "공시",
  SENTIMENT_CHANGE: "감성",
  SECTOR_MOVE: "섹터",
  USER_MEMO: "메모",
  SIMULATION_TRADE: "모의매매",
};

export const EVENT_TONE: Record<string, BadgeTone> = {
  PRICE_SPIKE: "up",
  PRICE_DROP: "down",
  VOLUME_SURGE: "accent",
  DISCLOSURE_PUBLISHED: "disclosure",
};

export function eventTone(type: string): BadgeTone {
  return EVENT_TONE[type] ?? "muted";
}
