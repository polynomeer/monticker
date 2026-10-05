import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ConditionalOrderResponse } from "@monticker/types";

vi.mock("@/hooks/useBrokerage", () => ({ useCancelConditionalOrder: () => ({ mutateAsync: vi.fn(), isPending: false }) }));
vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

import { ConditionalStatusCell } from "@/components/brokerage/ConditionalOrderRow";

function order(over: Partial<ConditionalOrderResponse>): ConditionalOrderResponse {
  return {
    id: 1, symbol: "005930", side: "SELL", triggerType: "STOP_LOSS", triggerPrice: 70000, orderType: "MARKET",
    limitPrice: null, quantity: 10, ocoGroupId: null, status: "ACTIVE", failReason: null, executedOrderId: null,
    createdAt: "2026-10-04T01:00:00Z", triggeredAt: null, expiresAt: null, priceFeed: "LIVE", ...over,
  };
}

describe("ConditionalOrderRow — 실시세 상태 (ADR-060)", () => {
  it("실시세가 연결돼 있으면 경고하지 않는다", () => {
    render(<ConditionalStatusCell o={order({})} />);
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("실시세 대상이 아니면 발동하지 않는다고 분명히 알린다", () => {
    render(<ConditionalStatusCell o={order({ priceFeed: "NONE" })} />);
    expect(screen.getByRole("status")).toHaveTextContent("발동하지 않습니다");
  });

  it("장중에 끊겼으면 끊긴 동안 발동하지 않는다고 알린다", () => {
    render(<ConditionalStatusCell o={order({ priceFeed: "STALE" })} />);
    expect(screen.getByRole("status")).toHaveTextContent("끊겼습니다");
  });
});
