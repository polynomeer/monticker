// Strategy Market creator earnings/payout types.
// Mirrors backend/api/.../settlement/creator/api endpoints under /api/settlement/strategy/*.

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface EarningSummary {
  strategyId: number;
  totalNet: number;
}

// GET /api/settlement/strategy/earnings/summary
export interface EarningsSummaryResponse {
  availableBalance: number;
  byStrategy: EarningSummary[];
}

export type CreatorEarningStatus = "AVAILABLE" | "PAID_OUT" | "CANCELLED";

export interface CreatorEarning {
  id: number;
  strategyId: number;
  subscriberId: number;
  grossAmount: number;
  platformFee: number;
  netAmount: number;
  status: CreatorEarningStatus;
  earnedAt: string;
}

export type CreatorPayoutStatus = "REQUESTED" | "APPROVED" | "REJECTED" | "PAID";

export interface CreatorPayout {
  id: number;
  amount: number;
  bankName: string | null;
  accountNumber: string | null;
  accountHolder: string | null;
  status: CreatorPayoutStatus;
  rejectReason: string | null;
  requestedAt: string;
  processedAt: string | null;
}

// GET /api/quant/market/creator/dashboard — 제작자 대시보드 집계(수익은 취소분 제외, 월은 KST)
export interface MonthlyNetPoint {
  /** YYYY-MM */
  month: string;
  net: number;
}

export interface CreatorStrategyRow {
  /** strategy_market.id — 수익 내역의 strategyId와 같다 */
  marketId: number;
  rulesetId: string;
  name: string;
  price: number;
  subscribers: number;
  thisMonthNet: number;
  totalNet: number;
  sharedAt: string | null;
}

export interface CreatorDashboard {
  /** 최근 12개월, 이번 달 포함. 수익 없는 달은 0 */
  monthly: MonthlyNetPoint[];
  thisMonthNet: number;
  totalNet: number;
  activeSubscribers: number;
  strategies: CreatorStrategyRow[];
}
