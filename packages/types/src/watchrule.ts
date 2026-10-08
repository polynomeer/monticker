// ADR-051 — 이벤트 트리거 모의 자동주문(watch rule).
// 백엔드 com.monticker.api.watchrule.api 의 요청·응답과 형태를 맞춘다.

/** worker DetectedEventType 과 같은 값. 탐지기가 늘면 여기에 추가한다. */
export type WatchRuleDetectedEventType = "PRICE_SPIKE" | "PRICE_DROP" | "VOLUME_SURGE";

/** 감지 원인 — 탐지 이벤트 또는 퀀트랩 전략 신호(ADR-077). */
export type WatchRuleEventType = WatchRuleDetectedEventType | "QUANT_SIGNAL";

export type WatchRuleSide = "BUY" | "SELL";

/** ADR-095 — 대상: 종목 하나 또는 내 관심종목 그룹(평가 시점 구성 종목마다 판정). */
export type WatchRuleTargetType = "STOCK" | "GROUP";

/** ADR-095 — 발동 주문 유형. LIMIT은 발동 시점 가격 × (1 + limitOffsetBps/10000)의 지정가. */
export type WatchRuleOrderType = "MARKET" | "LIMIT";

/** ADR-095 — 수량 기준: 주 수 또는 모의 계좌 평가자산의 %(발동 시점 계산, 정수 주로 내림). */
export type WatchRuleSizeType = "SHARES" | "EQUITY_PCT";

/**
 * EXECUTED: 주문 체결 / PLACED: 지정가 접수(미체결, ADR-095) / FILLED: 접수했던 지정가가 이후 체결(ADR-098) /
 * CANCELLED: 접수했던 지정가가 체결 전에 취소(ADR-098) / REJECTED: 리스크 게이트·잔고 등으로 거부 /
 * SKIPPED: 쿨다운·중요도 미달·0주 등. 거부와 건너뜀도 이유와 함께 남는다 — 사용자가 "왜 안 샀지"를 확인할 수 있어야 한다.
 */
export type WatchRuleExecutionStatus = "EXECUTED" | "PLACED" | "FILLED" | "CANCELLED" | "REJECTED" | "SKIPPED";

export interface WatchRuleResponse {
  id: number;
  /** 그룹 규칙이면 null */
  stockId: number | null;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  /** 계좌 % 규칙이면 null */
  quantity: number | null;
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
  /** 오늘(KST) 발동 수 — 서버가 한도를 집행하는 카운터 */
  todayExecutions?: number;
  /** ADR-095 */
  targetType?: WatchRuleTargetType;
  targetGroupId?: number | null;
  /** 그룹 이름. 그룹이 지워졌으면 null이고 targetGroupMissing = true(규칙은 꺼진다) */
  targetGroupName?: string | null;
  targetGroupMissing?: boolean;
  orderType?: WatchRuleOrderType;
  limitOffsetBps?: number | null;
  sizeType?: WatchRuleSizeType;
  equityPct?: number | null;
}

export interface CreateWatchRuleRequest {
  /** 대상이 종목일 때 */
  stockId?: number;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  /** 수량 기준이 주 수일 때 */
  quantity?: number;
  minImportanceScore?: number;
  cooldownSec?: number;
  name?: string;
  ruleSetId?: string;
  signalDirection?: WatchRuleSide;
  requiredEventTypes?: WatchRuleDetectedEventType[];
  conditionWindowSec?: number;
  dailyLimit?: number;
  /** ADR-095 — 기본 STOCK. GROUP이면 targetGroupId(내 관심종목 그룹) */
  targetType?: WatchRuleTargetType;
  targetGroupId?: number;
  /** 기본 MARKET. LIMIT이면 limitOffsetBps(−1000~1000) */
  orderType?: WatchRuleOrderType;
  limitOffsetBps?: number;
  /** 기본 SHARES. EQUITY_PCT면 equityPct(1~25) */
  sizeType?: WatchRuleSizeType;
  equityPct?: number;
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
  /** ADR-095 — 지정가 규칙의 오프셋 / 계좌 % 규칙의 비율 */
  limitOffsetBps?: number;
  equityPct?: number;
  /**
   * ADR-098 — 기준 바꾸기(생성과 같은 검증). 기준을 바꾸면 새 기준의 값이 함께 와야 한다:
   * GROUP → targetGroupId, STOCK → stockId, LIMIT → limitOffsetBps, EQUITY_PCT → equityPct, SHARES → quantity.
   */
  targetType?: WatchRuleTargetType;
  stockId?: number;
  targetGroupId?: number;
  orderType?: WatchRuleOrderType;
  sizeType?: WatchRuleSizeType;
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
  /** ADR-095 — 발동 종목(그룹 규칙은 발동마다 다르다) */
  stockId?: number | null;
  /** 지정가 발동의 지정가 */
  limitPrice?: number | null;
  /** ADR-098 — PLACED 이후 결과(FILLED·CANCELLED)가 정해진 시각. FILLED면 fillPrice가 체결가 */
  resolvedAt?: string | null;
}
