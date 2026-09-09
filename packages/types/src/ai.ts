// AI 주문 제안 — ADR-036. GET/POST /api/ai/order-proposals*
export type OrderProposalSide = "BUY" | "SELL" | "HOLD";
export type OrderProposalStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface OrderProposal {
  id: number;
  stockId: number;
  side: OrderProposalSide;
  reasoning: string;
  status: OrderProposalStatus;
  createdAt: string;
  expiresAt: string;
}
