import { describe, expect, it } from "vitest";
import { sellableQuantity, type PaperOpenOrder } from "@/hooks/usePaperTrade";

const order = (o: Partial<PaperOpenOrder>): PaperOpenOrder => ({
  id: 1, stockId: 2, side: "SELL", orderType: "LIMIT", quantity: 5, limitPrice: 70000, filledQty: 0,
  avgFillPrice: null, status: "PENDING", rejectReason: null, createdAt: "2026-10-05T00:00:00Z", ...o,
});

// ADR-074 — 서버 사가와 같은 규칙: 매도 가능 = 보유 − 미체결 매도 잔량
describe("sellableQuantity", () => {
  it("subtracts the open SELL remainder of the same stock only", () => {
    const open = [
      order({ id: 1, quantity: 5, filledQty: 1 }),          // 잔량 4
      order({ id: 2, side: "BUY", quantity: 3 }),           // 매수는 무관
      order({ id: 3, stockId: 9, quantity: 7 }),            // 다른 종목은 무관
    ];
    expect(sellableQuantity(10, open, 2)).toBe(6);
  });

  it("never goes below zero", () => {
    expect(sellableQuantity(2, [order({ quantity: 5 })], 2)).toBe(0);
  });
});
