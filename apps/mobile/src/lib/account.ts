/** 내 계좌 화면 계산 — 순수 함수라 노드 테스트로 확인한다 */

export interface LedgerRow { eventType: string; amount: number; description: string | null }

/** 원장 이벤트 → 돈이 어디서 어디로 갔는지(시안 "현금 → 예약금") */
export function ledgerFlow(eventType: string, amount: number): string {
  switch (eventType) {
    case "CASH_RESERVED": return "현금 → 예약금";
    case "CASH_UNRESERVED": return "예약금 → 현금";
    case "FILL":
    case "PARTIAL_FILL": return amount < 0 ? "현금 → 평가액" : "평가액 → 현금";
    case "DEPOSIT": return "입금 → 현금";
    case "WITHDRAWAL": return "현금 → 출금";
    case "FEE": return "현금 → 수수료";
    case "SETTLEMENT":
    case "PAPER_SETTLEMENT_COMPLETE": return "정산";
    default: return "";
  }
}

const TITLE: Record<string, string> = {
  CASH_RESERVED: "주문 예약",
  CASH_UNRESERVED: "예약 해제",
  FILL: "체결",
  PARTIAL_FILL: "부분 체결",
  DEPOSIT: "입금",
  WITHDRAWAL: "출금",
  FEE: "수수료",
  SETTLEMENT: "정산",
  PAPER_SETTLEMENT_COMPLETE: "정산 완료",
};

export function ledgerTitle(row: LedgerRow): string {
  return row.description?.trim() || TITLE[row.eventType] || row.eventType;
}

/** 예약 관련 이동은 주의색, 나머지 지출은 보조색 */
export function ledgerTone(eventType: string): "caution" | "muted" | "positive" {
  if (eventType === "CASH_RESERVED") return "caution";
  if (eventType === "DEPOSIT" || eventType === "CASH_UNRESERVED") return "positive";
  return "muted";
}

/** 막대 비율 — 아주 작은 칸도 보이게 최소 폭을 둔다(%) */
export function allocation(parts: number[], minPct = 1.5): number[] {
  const total = parts.reduce((a, b) => a + Math.max(0, b), 0);
  if (total <= 0) return parts.map(() => 0);
  return parts.map((p) => (p > 0 ? Math.max(minPct, (p / total) * 100) : 0));
}

export interface EquityPoint { equity: number; drawdown: number }

/** 포워드 테스트 누적 수익(%)과 최대 낙폭(%, 음수) */
export function forwardStats(initialCapital: number, currentEquity: number, curve: EquityPoint[]): { returnPct: number | null; mddPct: number | null } {
  const returnPct = initialCapital > 0 ? ((currentEquity - initialCapital) / initialCapital) * 100 : null;
  const mdd = curve.length ? Math.min(...curve.map((p) => p.drawdown)) : null;
  // 서버 drawdown은 비율(−0.061)이다. 퍼센트로
  const mddPct = mdd == null ? null : Math.abs(mdd) <= 1 ? mdd * 100 : mdd;
  return { returnPct, mddPct };
}

/** 시작한 지 몇 주째인지(1주째부터) */
export function weeksSince(iso: string, now: Date): number {
  return Math.max(1, Math.floor((now.getTime() - Date.parse(iso)) / (7 * 86_400_000)) + 1);
}
