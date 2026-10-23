import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";

const createAsync = vi.fn();
const ocoAsync = vi.fn();
const emptyPage = { data: { content: [], totalPages: 0 }, isLoading: false, refetch: vi.fn() };

vi.mock("@/services/auth", () => ({ getAccessToken: () => "token" }));
vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock("@/hooks/useBrokerage", () => ({
  useBrokerageAccount: () => ({ data: { provider: "KIS", accountNumber: "1234567801" }, isLoading: false }),
  useConditionalOrders: () => emptyPage,
  useConditionalOrderQuotes: () => ({ data: [] }),
  useConditionalOrderStats: () => ({ data: undefined }),
  useConditionalTriggers: () => emptyPage,
  useCreateConditionalOrder: () => ({ mutateAsync: createAsync, isPending: false }),
  useCreateOcoOrder: () => ({ mutateAsync: ocoAsync, isPending: false }),
  useCancelConditionalOrder: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useTradingStatus: () => ({ data: { halted: false } }),
}));
// 셸(상단 바·레일)은 이 테스트의 관심사가 아니다 — 본문만 그린다.
vi.mock("@/components/terminal", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/components/terminal")>()),
  TerminalPage: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock("@/components/brokerage/shared", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/components/brokerage/shared")>()),
  StockSearchBox: ({ onSelect }: { onSelect: (h: { id: number; symbol: string; name: string }) => void }) => (
    <button type="button" onClick={() => onSelect({ id: 9, symbol: "005930", name: "삼성전자" })}>종목 고르기</button>
  ),
}));

import ConditionalOrderPage from "@/app/brokerage/conditional-orders/page";

describe("조건부 주문 등록 — 확인 창", () => {
  beforeEach(() => {
    createAsync.mockReset();
    createAsync.mockResolvedValue({});
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, json: async () => ({ price: 70000 }) })));
  });

  async function fillSingle() {
    render(<ConditionalOrderPage />);
    await userEvent.click(await screen.findByRole("button", { name: "종목 고르기" }));
    await waitFor(() => expect(screen.getByPlaceholderText("현재가 70,000")).toBeInTheDocument());
    await userEvent.type(screen.getByPlaceholderText("현재가 70,000"), "65000");
  }

  it("폼 탭 이름은 '새 조건부 주문'이고, 등록 버튼은 바로 제출하지 않고 확인 창을 연다", async () => {
    await fillSingle();
    expect(screen.getByText("새 조건부 주문")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "조건부 주문 등록" }));
    expect(createAsync).not.toHaveBeenCalled();

    const dialog = screen.getByRole("dialog", { name: "실계좌 조건부 주문을 등록할까요?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveTextContent("••••-••01");
    expect(dialog).toHaveTextContent("삼성전자 005930");
    expect(dialog).toHaveTextContent("손절 · 65,000원 이하");
    expect(dialog).toHaveTextContent("시장가");
    expect(dialog).toHaveTextContent("매도 1주");
    expect(dialog).toHaveTextContent("90일");
    expect(dialog).toHaveTextContent("조건 충족 시 확인 없이 실계좌로 제출됩니다");
    // 처음 포커스는 취소 — Enter 버릇으로 확정되지 않게
    expect(screen.getByRole("button", { name: "취소" })).toHaveFocus();
  });

  it("등록을 눌러야 제출하고, 취소하면 제출하지 않는다", async () => {
    await fillSingle();
    await userEvent.click(screen.getByRole("button", { name: "조건부 주문 등록" }));
    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(createAsync).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: "조건부 주문 등록" }));
    await userEvent.click(screen.getByRole("button", { name: "등록" }));
    expect(createAsync).toHaveBeenCalledTimes(1);
    expect(createAsync.mock.calls[0][0]).toMatchObject({
      symbol: "005930", side: "SELL", quantity: 1,
      leg: { triggerType: "STOP_LOSS", triggerPrice: 65000, orderType: "MARKET" },
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});
