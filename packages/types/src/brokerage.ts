// Real brokerage (BYOK) — account connect, balance, orders, settlements.
// Mirrors backend/api/.../brokerage/api/BrokerageController.kt.

export type BrokerageOrderSide = "BUY" | "SELL";
export type BrokerageOrderType = "MARKET" | "LIMIT";
// ADR-056 — PENDING_SUBMIT(제출 중), UNKNOWN(증권사 응답 없음 — 체결됐을 수 있다). 서버 대조 잡이 해소한다.
export type BrokerageOrderStatus =
  | "PENDING_SUBMIT" | "SUBMITTED" | "UNKNOWN" | "FILLED" | "PARTIALLY_FILLED" | "CANCELLED" | "REJECTED";
export type BrokerageSettlementStatus = "PENDING" | "SETTLED" | "FAILED";
// ADR-026 — 사용자가 선택 가능한 실제 증권사만. MOCK은 서버 설정(app.brokerage.mock.enabled)에
// 따른 내부 구현 디테일이라 프론트에서 선택하지 않는다.
export type BrokerageProviderId = "KIS" | "TOSS";

export interface ConnectBrokerageRequest {
  provider: BrokerageProviderId;
  appKey: string;
  appSecret: string;
  accountNumber: string;
  /** ADR-068 — 연동 고지 동의. 셋 모두 필수. */
  consents: BrokerageConsent[];
}

export type BrokerageConsent = "BROKERAGE_DELEGATION" | "BROKERAGE_NO_CUSTODY" | "BROKERAGE_LOSS_ATTRIBUTION";

export interface BrokerageAccountResponse {
  id: number;
  provider: string;
  accountNumber: string;
  accountType: string;
  isActive: boolean;
  connectedAt: string;
  tokenValid: boolean;
  /** 이 서버가 관측한 마지막 증권사 호출 상태. 관측이 없으면 null/생략. 오류는 고정 코드만 온다. */
  apiHealth?: BrokerageApiHealth | null;
}

export type BrokerCallOperation =
  | "SUBMIT_ORDER" | "CANCEL_ORDER" | "GET_ORDER_STATUS" | "GET_SETTLEMENTS" | "FIND_ORDERS" | "GET_BALANCE";

export type BrokerErrorCode =
  | "TIMEOUT" | "NETWORK" | "CIRCUIT_OPEN" | "BROKER_UNAVAILABLE" | "AUTH_FAILED" | "RATE_LIMITED"
  | "BROKER_4XX" | "BROKER_5XX" | "SUBMIT_INDETERMINATE" | "ORDER_REJECTED" | "LOOKUP_FAILED" | "UNKNOWN";

export interface BrokerageApiHealth {
  lastLatencyMs: number;
  lastCallAt: string;
  lastOperation: BrokerCallOperation;
  lastSuccessAt: string | null;
  lastErrorCode: BrokerErrorCode | null;
  lastErrorAt: string | null;
  lastErrorOperation: BrokerCallOperation | null;
}

export interface BrokerageHolding {
  symbol: string;
  quantity: number;
  avgPrice: number;
  currentPrice: number;
}

export interface BrokerageBalanceResponse {
  cash: number;
  totalEvaluated: number;
  holdings: BrokerageHolding[];
}

export interface SubmitBrokerageOrderRequest {
  symbol: string;
  side: BrokerageOrderSide;
  orderType: BrokerageOrderType;
  quantity: number;
  limitPrice?: number;
}

export interface BrokerageOrderResponse {
  id: number;
  symbol: string;
  side: BrokerageOrderSide;
  orderType: BrokerageOrderType;
  quantity: number;
  limitPrice: number | null;
  filledQty: number;
  avgFillPrice: number | null;
  pgOrderId: string | null;
  status: BrokerageOrderStatus;
  rejectReason: string | null;
  submittedAt: string;
  filledAt: string | null;
  /** ADR-056 — 결과 불명 주문을 자동으로 확정할 수 없어 사람이 확인해야 한다. */
  needsReview: boolean;
}

/** ADR-057 — 이 사용자의 실주문을 막는 킬 스위치. 사용자 범위(USER)는 사유를 숨긴 문구다. */
export interface TradingStatusResponse {
  halted: boolean;
  scope: "GLOBAL" | "PROVIDER" | "USER" | null;
  message: string | null;
}

export interface BrokerageSettlementResponse {
  id: number;
  symbol: string;
  side: BrokerageOrderSide;
  quantity: number;
  fillPrice: number;
  grossAmount: number;
  fee: number;
  tax: number;
  netAmount: number;
  settleDate: string;
  status: BrokerageSettlementStatus;
  settledAt: string | null;
}

// ADR-032 — 조건부 주문(스탑로스/익절/OCO).
// 4가지 타입 중 STOP_LOSS/PRICE_BELOW는 "현재가 <= triggerPrice",
// TAKE_PROFIT/PRICE_ABOVE는 "현재가 >= triggerPrice"일 때 발동한다.
export type ConditionalTriggerType = "STOP_LOSS" | "TAKE_PROFIT" | "PRICE_ABOVE" | "PRICE_BELOW";
export type ConditionalOrderStatus = "ACTIVE" | "TRIGGERED" | "EXECUTED" | "CANCELLED" | "EXPIRED" | "FAILED";

export interface ConditionalOrderLegRequest {
  triggerType: ConditionalTriggerType;
  triggerPrice: number;
  orderType: BrokerageOrderType;
  limitPrice?: number;
}

export interface CreateConditionalOrderRequest {
  symbol: string;
  side: BrokerageOrderSide;
  quantity: number;
  leg: ConditionalOrderLegRequest;
  /** 유효 기간(일) 1~90. 생략하면 90. KST 날짜로 오늘+N일까지 유효. */
  validDays?: number;
}

export interface CreateOcoOrderRequest {
  symbol: string;
  side: BrokerageOrderSide;
  quantity: number;
  legs: ConditionalOrderLegRequest[];
  validDays?: number;
}

export interface ConditionalOrderStatsResponse {
  byStatus: Record<ConditionalOrderStatus, number>;
  total: number;
  firedThisMonth: number;
  monthStart: string;
}

export interface ConditionalTriggerEvent {
  conditionalOrderId: number;
  symbol: string;
  side: BrokerageOrderSide;
  triggerType: ConditionalTriggerType;
  triggerPrice: number;
  orderType: BrokerageOrderType;
  limitPrice: number | null;
  quantity: number;
  ocoGroupId: string | null;
  triggeredAt: string;
  status: ConditionalOrderStatus;
  failReason: string | null;
  executedOrderId: number | null;
  orderStatus: BrokerageOrderStatus | null;
  filledQty: number | null;
  avgFillPrice: number | null;
}

export interface ConditionalTriggerPage {
  content: ConditionalTriggerEvent[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ConditionalOrderQuote {
  stockId: number;
  symbol: string;
  name: string;
  /** 최근 1분봉 종가. 없으면 null */
  price: number | null;
  priceAt: string | null;
}

export interface ConditionalOrderResponse {
  id: number;
  stockId: number;
  symbol: string;
  side: BrokerageOrderSide;
  triggerType: ConditionalTriggerType;
  triggerPrice: number;
  orderType: BrokerageOrderType;
  limitPrice: number | null;
  quantity: number;
  ocoGroupId: string | null;
  status: ConditionalOrderStatus;
  failReason: string | null;
  executedOrderId: number | null;
  createdAt: string;
  triggeredAt: string | null;
  expiresAt: string | null;
  /**
   * ADR-060 — ACTIVE일 때만. LIVE: 실시세 연결. STALE: 장중 이 종목 실시세가 끊겼다. NONE: 실시세 대상이 아니다.
   * STALE·NONE이면 조건을 만족해도 발동하지 않는다.
   */
  priceFeed?: "LIVE" | "STALE" | "NONE" | null;
}

// ADR-034 — 리밸런싱 실행 자동화(실브로커리지, 수동 실행).
export type RebalanceTargetSource = "OPTIMIZER" | "MANUAL";
export type RebalanceExecutionStatus = "EXECUTING" | "COMPLETED" | "PARTIALLY_FAILED";
export type RebalanceLegStatus = "EXECUTED" | "FAILED" | "UNKNOWN";  // UNKNOWN — ADR-056, 주문 결과 확인 중

export interface SaveRebalanceTargetRequest {
  weights: Record<string, number>;
  thresholdPct: number;
  source: RebalanceTargetSource;
}

export interface RebalanceTargetResponse {
  id: number;
  weights: Record<string, number>;
  thresholdPct: number;
  source: RebalanceTargetSource;
  updatedAt: string;
}

export interface RebalanceLegResponse {
  symbol: string;
  side: BrokerageOrderSide;
  targetWeight: number;
  currentWeight: number;
  diffPct: number;
  quantity: number;
  /** 수량을 계산한 추정 가격. 실행은 시장가 — 이 가격으로 주문하지 않는다. */
  estimatedPrice: number;
  priceSource: "BROKER_BALANCE" | "LAST_CANDLE";
  estimatedAmount: number;
  estimatedFee: number;
  estimatedTax: number;
}

export interface RebalanceCostModel {
  feeRate: number;
  sellTaxRate: number;
  basis: "ESTIMATE";
  note: string;
}

export interface RebalancePreviewResponse {
  totalValue: number;
  legs: RebalanceLegResponse[];
  estimatedFee: number;
  estimatedTax: number;
  /** 수수료 + 매도 거래세(추정) */
  estimatedCost: number;
  estimatedBuyAmount: number;
  estimatedSellAmount: number;
  /** 매수 중 지금 보유하지 않은 종목 */
  estimatedNewBuyAmount: number;
  costModel: RebalanceCostModel;
}

export interface RebalanceExecutionLegResponse {
  symbol: string;
  side: BrokerageOrderSide;
  quantity: number;
  status: RebalanceLegStatus;
  executedOrderId: number | null;
  failReason: string | null;
}

export interface RebalanceExecutionResponse {
  id: number;
  status: RebalanceExecutionStatus;
  requestedAt: string;
  completedAt: string | null;
  legs: RebalanceExecutionLegResponse[];
}
