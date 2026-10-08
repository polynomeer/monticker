import { describe, expect, it } from "vitest";
import { buildReceiptSteps, type ReceiptOrder } from "@/components/wallet/receiptSteps";
import { fmtTime } from "@/components/portfolio/format";

const T0 = "2026-10-08T00:30:00Z";
const T1 = "2026-10-08T00:30:00.003Z";
const F1 = "2026-10-08T00:32:00Z";
const F2 = "2026-10-08T00:35:00Z";

function order(over: Partial<ReceiptOrder> = {}): ReceiptOrder {
  return {
    orderId: 1, orderType: "LIMIT", side: "BUY", quantity: 10, filledQty: 10, limitPrice: 70000, status: "FILLED",
    submittedAt: T0, reservedAt: T1, reservedAmount: 700000, reservedQty: null, cancelledAt: null, cancelReason: null,
    fills: [{ fillId: 11, quantity: 10, price: 69800, filledAt: F1, thisTrade: true }],
    ...over,
  };
}

describe("buildReceiptSteps (ADR-096)", () => {
  it("a filled BUY LIMIT has four steps with the cash reservation", () => {
    const steps = buildReceiptSteps({ side: "BUY", tradedAt: F1, order: order() }, null);
    expect(steps.map((s) => s.name)).toEqual(["주문 접수", "예약금 잠금", "체결", "정산"]);
    expect(steps[0].t).toBe(fmtTime(T0));
    expect(steps[1].t).toBe(fmtTime(T1));
    expect(steps[1].detail).toBe("700,000원");
    expect(steps[2].state).toBe("done");
    expect(steps[3]).toMatchObject({ state: "pending", t: "T+2 예정" });
  });

  it("a SELL LIMIT locks shares instead of cash", () => {
    const steps = buildReceiptSteps(
      { side: "SELL", tradedAt: F1, order: order({ side: "SELL", reservedAmount: null, reservedQty: 10 }) },
      { status: "SETTLED", settleDate: "2026-10-12", settledAt: "2026-10-12T00:00:00Z" },
    );
    expect(steps[1]).toMatchObject({ name: "매도 수량 잠금", detail: "10주" });
    expect(steps[3].state).toBe("done");
  });

  it("market orders and pre ADR-047 trades keep the three-step view", () => {
    const market = buildReceiptSteps({ side: "BUY", tradedAt: F1, order: order({ orderType: "MARKET", reservedAmount: null }) }, null);
    expect(market.map((s) => s.key)).toEqual(["submit", "fill", "settle"]);
    const legacy = buildReceiptSteps({ side: "BUY", tradedAt: F1, order: null }, null);
    expect(legacy.map((s) => s.key)).toEqual(["submit", "fill", "settle"]);
    expect(legacy[0].t).toBe(fmtTime(F1));
  });

  it("a partially filled order shows progress and this trade's fill time", () => {
    const o = order({
      filledQty: 7, status: "PARTIALLY_FILLED",
      fills: [
        { fillId: 11, quantity: 3, price: 70000, filledAt: F1, thisTrade: false },
        { fillId: 12, quantity: 4, price: 69900, filledAt: F2, thisTrade: true },
      ],
    });
    const fill = buildReceiptSteps({ side: "BUY", tradedAt: F2, order: o }, null)[2];
    expect(fill).toMatchObject({ name: "부분 체결", state: "partial", detail: "7/10주", t: fmtTime(F2) });
  });

  it("a partial fill whose remainder was cancelled says so", () => {
    const o = order({ filledQty: 2, status: "CANCELLED", cancelledAt: F2, cancelReason: "체결 시점 리스크 한도 초과: Concentration" });
    const fill = buildReceiptSteps({ side: "BUY", tradedAt: F1, order: o }, null)[2];
    expect(fill.name).toBe("부분 체결 · 잔량 취소");
    expect(fill.state).toBe("cancelled");
    expect(fill.t).toContain(`취소 ${fmtTime(F2, false)}`);
  });

  it("an old LIMIT row without a reservation time still shows the step, without a time", () => {
    const steps = buildReceiptSteps({ side: "BUY", tradedAt: F1, order: order({ reservedAt: null }) }, null);
    expect(steps[1]).toMatchObject({ name: "예약금 잠금", t: "시각 기록 없음", state: "done" });
  });
});
