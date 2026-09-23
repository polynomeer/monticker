// ADR-051 — 이벤트 트리거 모의 자동주문(watch rule).
// 백엔드 com.monticker.api.watchrule.api 의 요청·응답과 형태를 맞춘다.

/** worker DetectedEventType 과 같은 값. 탐지기가 늘면 여기에 추가한다. */
export type WatchRuleEventType = "PRICE_SPIKE" | "PRICE_DROP" | "VOLUME_SURGE";

export type WatchRuleSide = "BUY" | "SELL";

/**
 * EXECUTED: 주문 체결 / REJECTED: 리스크 게이트·잔고 등으로 거부 / SKIPPED: 쿨다운·중요도 미달.
 * 거부와 건너뜀도 이유와 함께 남는다 — 사용자가 "왜 안 샀지"를 확인할 수 있어야 한다.
 */
export type WatchRuleExecutionStatus = "EXECUTED" | "REJECTED" | "SKIPPED";

export interface WatchRuleResponse {
  id: number;
  stockId: number;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  quantity: number;
  /** 이벤트 importance_score 가 이 값 미만이면 발동하지 않는다(0~100). */
  minImportanceScore: number;
  /** 직전 체결로부터 이 시간 안에는 다시 발동하지 않는다. */
  cooldownSec: number;
  isActive: boolean;
  createdAt: string;
}

export interface CreateWatchRuleRequest {
  stockId: number;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  quantity: number;
  minImportanceScore?: number;
  cooldownSec?: number;
}

export interface UpdateWatchRuleRequest {
  quantity?: number;
  minImportanceScore?: number;
  cooldownSec?: number;
  isActive?: boolean;
}

export interface WatchRuleExecutionResponse {
  id: number;
  watchRuleId: number;
  stockEventId: number;
  status: WatchRuleExecutionStatus;
  orderId: number | null;
  fillPrice: number | null;
  quantity: number | null;
  reason: string | null;
  createdAt: string;
}
