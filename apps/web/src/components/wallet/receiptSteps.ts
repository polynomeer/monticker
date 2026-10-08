import { fmtNum } from "@/components/terminal";
import { fmtMonthDay, fmtTime } from "@/components/portfolio/format";

/** ADR-096 — 영수증 주문의 체결 한 건 */
export interface ReceiptFill { fillId: number; quantity: number; price: number; filledAt: string; thisTrade: boolean; }

/** ADR-096 — 영수증 거래를 만든 주문의 진행 기록. ADR-047 이전 거래는 null. */
export interface ReceiptOrder {
  orderId: number;
  orderType: "MARKET" | "LIMIT";
  side: "BUY" | "SELL";
  quantity: number;
  filledQty: number;
  limitPrice: number | null;
  status: string;
  submittedAt: string;
  /** V93 이전 주문은 null — 잠금은 있었지만 시각이 없다 */
  reservedAt: string | null;
  reservedAmount: number | null;
  reservedQty: number | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  fills: ReceiptFill[];
}

export interface PaperSettlement { status: "PENDING" | "SETTLED" | "FAILED"; settleDate: string; settledAt: string | null; }

export type StepState = "done" | "partial" | "pending" | "cancelled";
export interface ReceiptStep { key: "submit" | "reserve" | "fill" | "settle"; name: string; t: string; state: StepState; detail?: string; }

interface ReceiptLike { side: string; tradedAt: string; order?: ReceiptOrder | null; }

/**
 * 영수증 단계. 지정가는 접수 → 예약 잠금 → 체결(부분 포함) → 정산, 시장가·주문 기록 없는 옛 거래는 접수 → 체결 → 정산.
 * 정산은 이 거래(체결 한 건)의 T+2 정산 기록이다 — 부분 체결이면 체결마다 따로 정산된다.
 */
export function buildReceiptSteps(receipt: ReceiptLike, settlement: PaperSettlement | null | undefined): ReceiptStep[] {
  const o = receipt.order ?? null;
  const thisFill = o?.fills.find((f) => f.thisTrade);
  const steps: ReceiptStep[] = [
    { key: "submit", name: "주문 접수", t: fmtTime(o?.submittedAt ?? receipt.tradedAt), state: "done" },
  ];

  if (o?.orderType === "LIMIT") {
    const buy = o.side === "BUY";
    steps.push({
      key: "reserve",
      name: buy ? "예약금 잠금" : "매도 수량 잠금",
      t: o.reservedAt ? fmtTime(o.reservedAt) : "시각 기록 없음",
      state: "done",
      detail: buy
        ? (o.reservedAmount != null ? `${fmtNum(o.reservedAmount)}원` : undefined)
        : (o.reservedQty != null ? `${fmtNum(o.reservedQty)}주` : undefined),
    });
  }

  const fillTime = fmtTime(thisFill?.filledAt ?? receipt.tradedAt);
  if (o && o.filledQty < o.quantity) {
    const cancelled = o.status === "CANCELLED";
    steps.push({
      key: "fill",
      name: cancelled ? "부분 체결 · 잔량 취소" : "부분 체결",
      t: cancelled && o.cancelledAt ? `${fillTime} · 취소 ${fmtTime(o.cancelledAt, false)}` : fillTime,
      state: cancelled ? "cancelled" : "partial",
      detail: `${fmtNum(o.filledQty)}/${fmtNum(o.quantity)}주`,
    });
  } else {
    steps.push({
      key: "fill",
      name: "체결",
      t: fillTime,
      state: "done",
      detail: o && o.fills.length > 1 ? `${o.fills.length}회 나눠 체결` : undefined,
    });
  }

  const settled = settlement?.status === "SETTLED";
  steps.push({
    key: "settle",
    name: "정산",
    t: settled ? fmtMonthDay(settlement?.settledAt) + " 완료" : settlement ? `${fmtMonthDay(settlement.settleDate)} 예정` : "T+2 예정",
    state: settled ? "done" : "pending",
  });
  return steps;
}
