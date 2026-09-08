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
