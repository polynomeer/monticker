export type EventType =
  | "PRICE_SPIKE"
  | "PRICE_DROP"
  | "VOLUME_SURGE"
  | "NEWS_PUBLISHED"
  | "DISCLOSURE_PUBLISHED"
  | "SECTOR_MOVE"
  | "SENTIMENT_CHANGE"
  | "USER_MEMO"
  | "SIMULATION_TRADE";

export interface StockEvent {
  id: number;
  stockId: number;
  eventType: EventType;
  title: string;
  description?: string | null;
  eventTime: string;
  importanceScore: number;
  sentimentScore?: number;
  sourceType?: string | null;
  /** 이벤트 구간 변동률(%) — /api/events/recent에서만 채운다 */
  windowChangePct?: number | null;
  /** 이벤트 직후 거래량 ÷ 직전 평균 — 위와 같음 */
  volumeMultiple?: number | null;
}
