import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";

const state = vi.hoisted(() => ({
  account: {} as Record<string, unknown>,
  orders: {} as Record<string, unknown>,
}));
const emptyPage = { data: { content: [], totalPages: 0 }, isLoading: false, refetch: vi.fn() };

vi.mock("@/services/auth", () => ({ getAccessToken: () => "token" }));
vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock("@/hooks/useBrokerage", () => ({
  useBrokerageAccount: () => state.account,
  useConditionalOrders: () => state.orders,
  useConditionalOrderQuotes: () => ({ data: [] }),
  useConditionalOrderStats: () => ({ data: undefined }),
  useConditionalTriggers: () => emptyPage,
  useCreateConditionalOrder: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useCreateOcoOrder: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useCancelConditionalOrder: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useTradingStatus: () => ({ data: { halted: false } }),
}));
vi.mock("@/components/terminal", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/components/terminal")>()),
  TerminalPage: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

import ConditionalOrderPage from "@/app/brokerage/conditional-orders/page";

const linked = { data: { provider: "KIS", accountNumber: "1234567801" }, isLoading: false, isError: false, refetch: vi.fn() };

describe("실전 화면 — 실패를 '없음'으로 보이지 않는다", () => {
  beforeEach(() => {
    state.account = linked;
    state.orders = { ...emptyPage, isError: false };
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, json: async () => ({}) })));
  });

  it("계좌 조회 실패는 연동 안내가 아니라 조회 실패로 보인다", async () => {
    state.account = { data: undefined, isLoading: false, isError: true, refetch: vi.fn() };
    render(<ConditionalOrderPage />);
    expect(await screen.findByText("계좌 정보를 불러오지 못했습니다")).toBeInTheDocument();
    expect(screen.queryByText("연동된 증권사 계좌가 없습니다")).not.toBeInTheDocument();
  });

  it("연동이 정말 없으면(409 → null) 연동 안내를 보인다", async () => {
    state.account = { data: null, isLoading: false, isError: false, refetch: vi.fn() };
    render(<ConditionalOrderPage />);
    expect(await screen.findByText("연동된 증권사 계좌가 없습니다")).toBeInTheDocument();
  });

  it("조건부 주문 조회 실패는 '감시 중 0'이나 빈 목록으로 보이지 않는다", async () => {
    state.orders = { data: undefined, isLoading: false, isError: true, refetch: vi.fn() };
    render(<ConditionalOrderPage />);
    expect(await screen.findByText(/감시 중인 조건부 주문을\(를\) 불러오지 못했습니다/)).toBeInTheDocument();
    expect(screen.queryByText("감시 중 0")).not.toBeInTheDocument();
    expect(screen.queryByText("감시 중인 조건부 주문이 없습니다.")).not.toBeInTheDocument();
  });
});
