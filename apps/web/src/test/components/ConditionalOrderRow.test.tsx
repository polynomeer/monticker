import { beforeEach, describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ConditionalOrderResponse } from "@monticker/types";

const cancelAsync = vi.fn();
vi.mock("@/hooks/useBrokerage", () => ({ useCancelConditionalOrder: () => ({ mutateAsync: cancelAsync, isPending: false }) }));
vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

import { COND_STATUS, ConditionalCancelButton, ConditionalStatusCell } from "@/components/brokerage/ConditionalOrderRow";

function order(over: Partial<ConditionalOrderResponse>): ConditionalOrderResponse {
  return {
    id: 1, stockId: 9, symbol: "005930", side: "SELL", triggerType: "STOP_LOSS", triggerPrice: 70000, orderType: "MARKET",
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

describe("ConditionalCancelButton — 감시 취소는 한 번 확인받는다", () => {
  beforeEach(() => { cancelAsync.mockReset(); cancelAsync.mockResolvedValue({}); });

  it("버튼 이름은 '감시 취소'다 — 연동 해지와 헷갈리는 '해지'를 쓰지 않는다", () => {
    render(<ConditionalCancelButton o={order({})} />);
    expect(screen.getByRole("button", { name: "감시 취소" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "해지" })).toBeNull();
    expect(COND_STATUS.CANCELLED.label).toBe("취소됨");
  });

  it("첫 클릭은 확인 창만 열고, 확인을 눌러야 취소를 요청한다", async () => {
    render(<ConditionalCancelButton o={order({})} />);
    await userEvent.click(screen.getByRole("button", { name: "감시 취소" }));

    const dialog = screen.getByRole("dialog", { name: "조건부 주문 감시를 취소할까요?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveTextContent("005930");
    expect(cancelAsync).not.toHaveBeenCalled();

    await userEvent.click(screen.getAllByRole("button", { name: "감시 취소" }).at(-1)!);
    expect(cancelAsync).toHaveBeenCalledWith(1);
  });

  it("그대로 두기·Esc로 닫으면 요청하지 않는다", async () => {
    render(<ConditionalCancelButton o={order({})} />);
    await userEvent.click(screen.getByRole("button", { name: "감시 취소" }));
    await userEvent.click(screen.getByRole("button", { name: "그대로 두기" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "감시 취소" }));
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(cancelAsync).not.toHaveBeenCalled();
  });

  it("감시 중이 아니면 버튼을 그리지 않는다", () => {
    render(<ConditionalCancelButton o={order({ status: "CANCELLED" })} />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});
