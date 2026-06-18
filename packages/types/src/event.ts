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
  description?: string;
  eventTime: string;
  importanceScore: number;
  sentimentScore?: number;
}
