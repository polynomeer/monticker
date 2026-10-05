// ADR-051 — 이벤트 트리거 모의 자동주문(watch rule).
// 백엔드 com.monticker.api.watchrule.api 의 요청·응답과 형태를 맞춘다.

/** worker DetectedEventType 과 같은 값. 탐지기가 늘면 여기에 추가한다. */
export type WatchRuleDetectedEventType = "PRICE_SPIKE" | "PRICE_DROP" | "VOLUME_SURGE";

/** 감지 원인 — 탐지 이벤트 또는 퀀트랩 전략 신호(ADR-077). */
export type WatchRuleEventType = WatchRuleDetectedEventType | "QUANT_SIGNAL";

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
  /** ADR-077 — 사용자가 붙인 이름 */
  name?: string | null;
  /** eventType = QUANT_SIGNAL일 때 전략(룰셋) id·이름과 신호 방향 */
  ruleSetId?: string | null;
  ruleSetName?: string | null;
  signalDirection?: WatchRuleSide | null;
  /** 복합 조건 — 주 이벤트 앞 conditionWindowSec 안에 함께 감지됐어야 하는 유형 */
  requiredEventTypes?: WatchRuleDetectedEventType[];
  conditionWindowSec?: number | null;
  /** 하루(KST) 최대 체결 횟수. null이면 제한 없음 */
  dailyLimit?: number | null;
  /** 오늘(KST) 체결 수 — 서버가 한도를 집행하는 카운터 */
  todayExecutions?: number;
}

export interface CreateWatchRuleRequest {
  stockId: number;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  quantity: number;
  minImportanceScore?: number;
  cooldownSec?: number;
  name?: string;
  ruleSetId?: string;
  signalDirection?: WatchRuleSide;
  requiredEventTypes?: WatchRuleDetectedEventType[];
  conditionWindowSec?: number;
  dailyLimit?: number;
}

export interface UpdateWatchRuleRequest {
  quantity?: number;
  minImportanceScore?: number;
  cooldownSec?: number;
  isActive?: boolean;
  /** 빈 문자열이면 이름을 지운다 */
  name?: string;
  /** 0이면 제한 해제 */
  dailyLimit?: number;
}

export interface WatchRuleExecutionResponse {
  id: number;
  watchRuleId: number;
  /** 이벤트 발동이면 stock_events.id, 전략 신호 발동이면 null(quantSignalId가 있다) */
  stockEventId: number | null;
  quantSignalId?: number | null;
  status: WatchRuleExecutionStatus;
  orderId: number | null;
  fillPrice: number | null;
  quantity: number | null;
  reason: string | null;
  createdAt: string;
}
