// Real brokerage (BYOK) — account connect, balance, orders, settlements.
// Mirrors backend/api/.../brokerage/api/BrokerageController.kt.

export type BrokerageOrderSide = "BUY" | "SELL";
export type BrokerageOrderType = "MARKET" | "LIMIT";
export type BrokerageOrderStatus = "SUBMITTED" | "FILLED" | "PARTIALLY_FILLED" | "CANCELLED" | "REJECTED";
export type BrokerageSettlementStatus = "PENDING" | "SETTLED" | "FAILED";
// ADR-026 — 사용자가 선택 가능한 실제 증권사만. MOCK은 서버 설정(app.brokerage.mock.enabled)에
// 따른 내부 구현 디테일이라 프론트에서 선택하지 않는다.
export type BrokerageProviderId = "KIS" | "TOSS";

export interface ConnectBrokerageRequest {
  provider: BrokerageProviderId;
  appKey: string;
  appSecret: string;
  accountNumber: string;
}

export interface BrokerageAccountResponse {
  id: number;
  provider: string;
  accountNumber: string;
  accountType: string;
  isActive: boolean;
  connectedAt: string;
  tokenValid: boolean;
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
}

export interface CreateOcoOrderRequest {
  symbol: string;
  side: BrokerageOrderSide;
  quantity: number;
  legs: ConditionalOrderLegRequest[];
}

export interface ConditionalOrderResponse {
  id: number;
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
}
