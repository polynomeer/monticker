import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("@/services/api", () => ({ authFetch: vi.fn() }));

import { ClobBook } from "@/components/matching/ClobBook";
import type { OrderBookData, OrderQueueSnapshot } from "@/components/matching/data";

const book: OrderBookData = {
  stockId: 2, symbol: "005930", currentPrice: 70000,
  asks: [{ price: 70100, quantity: 500, amount: 0 }],
  bids: [{ price: 70000, quantity: 800, amount: 0 }, { price: 69900, quantity: 300, amount: 0 }],
};

const queue: OrderQueueSnapshot = {
  stockId: 2, asOf: "2026-10-08T00:30:00Z",
  bids: [{
    price: 70000, orderCount: 4, quantity: 14,
    slices: [
      { mine: false, orderCount: 2, quantity: 9, orderId: null, startPosition: 1 },
      { mine: true, orderCount: 1, quantity: 3, orderId: 77, startPosition: 3 },
      { mine: false, orderCount: 1, quantity: 2, orderId: null, startPosition: 4 },
    ],
  }],
  asks: [],
  mine: [
    { orderId: 77, side: "BUY", price: 70000, position: 3, aheadCount: 2, aheadQuantity: 9, remainingQuantity: 3, levelOrderCount: 4 },
    { orderId: 78, side: "BUY", price: 68000, position: 1, aheadCount: 0, aheadQuantity: 0, remainingQuantity: 5, levelOrderCount: 2 },
  ],
};

describe("ClobBook queue slices (ADR-096)", () => {
  it("shows my queue position on the matching price and lists my orders outside the visible book", () => {
    render(<ClobBook book={book} loading={false} myOrders={[]} queue={queue} />);

    expect(screen.getByText("내 주문 · 대기 3번째")).toBeTruthy();
    expect(screen.getByLabelText("모의 대기 4건 14주")).toBeTruthy();
    expect(screen.getByLabelText("호가 범위 밖 내 주문").textContent).toContain("대기 1번째 / 2건");
    // 남의 조각은 건수·잔량만 — 주문 id를 담지 않는다
    expect(screen.getByTitle("다른 주문 2건 · 9주")).toBeTruthy();
    expect(screen.getByTitle("내 주문 ORD-77 · 3번째 · 3주")).toBeTruthy();
  });

  it("ignores a queue snapshot of another stock and falls back to the old remaining-qty label", () => {
    render(
      <ClobBook
        book={book} loading={false} queue={{ ...queue, stockId: 3 }}
        myOrders={[{ id: 5, stockId: 2, side: "BUY", orderType: "LIMIT", quantity: 4, limitPrice: 69900, filledQty: 1, avgFillPrice: null, status: "PARTIALLY_FILLED", rejectReason: null, createdAt: "" }]}
      />,
    );
    expect(screen.queryByText(/대기 3번째/)).toBeNull();
    expect(screen.getByText("내 주문 · 3주 대기")).toBeTruthy();
  });
});
