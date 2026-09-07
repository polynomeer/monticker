// Real brokerage (BYOK) — account connect, balance, orders, settlements.
// Mirrors backend/api/.../brokerage/api/BrokerageController.kt.

export type BrokerageOrderSide = "BUY" | "SELL";
export type BrokerageOrderType = "MARKET" | "LIMIT";
export type BrokerageOrderStatus = "SUBMITTED" | "FILLED" | "PARTIALLY_FILLED" | "CANCELLED" | "REJECTED";
export type BrokerageSettlementStatus = "PENDING" | "SETTLED" | "FAILED";

export interface ConnectBrokerageRequest {
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
