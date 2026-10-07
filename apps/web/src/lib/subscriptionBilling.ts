/** ADR-083 — GET /api/subscription/me의 다음 정기결제 필드. */
export interface NextBilling {
  nextBillingAt?: string | null;
  noChargeReason?: "FREE_PLAN" | "CANCELLED" | "NOT_ACTIVE" | "NO_BILLING_KEY" | null;
}

/** 다음 결제일 표시 — 청구 예정이 없으면 이유를 짧게. 날짜는 KST 기준(갱신 잡이 01:00 KST에 돈다). */
export function nextBillingText(sub: NextBilling | null | undefined): string {
  if (!sub) return "—";
  if (sub.nextBillingAt) return new Date(sub.nextBillingAt).toLocaleDateString("ko-KR", { timeZone: "Asia/Seoul" });
  switch (sub.noChargeReason) {
    case "FREE_PLAN": return "없음 (무료)";
    case "CANCELLED": return "없음 (해지됨)";
    case "NO_BILLING_KEY": return "카드 미등록";
    default: return "—";
  }
}
