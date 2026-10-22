import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

vi.mock("@/services/api", () => ({ authFetch: vi.fn() }));
vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ isLoggedIn: true }) }));
vi.mock("@/hooks/usePaperTrade", () => ({
  sellableQuantity: () => 0,
  usePaperOpenOrders: () => ({ data: [] }),
  usePaperOrder: () => ({ place: { mutateAsync: vi.fn(), isPending: false } }),
  usePaperPortfolio: () => ({ data: { cash: 0, totalValue: 0, totalPnl: 0, totalPnlRate: 0, holdings: [] } }),
}));

import OrderForm from "@/components/stock/OrderForm";

function renderForm(brokerageConnected?: boolean) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OrderForm stock={{ id: 1, symbol: "005930", name: "삼성전자" }} currentPrice={70000} brokerageConnected={brokerageConnected} />
    </QueryClientProvider>,
  );
}

describe("종목 상세 주문 패널 — 모의투자 전용", () => {
  it("모의/실전 전환 버튼을 그리지 않고 '모의투자 주문'으로만 표시한다", () => {
    renderForm(true);
    expect(screen.getByText("모의투자 주문")).toBeInTheDocument();
    expect(screen.getByText("모의")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /실전/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "모의투자" })).toBeNull();
  });

  it("계좌가 연동돼 있으면 이 종목의 실전 주문 화면으로 안내한다", () => {
    renderForm(true);
    expect(screen.getByRole("link", { name: "실전투자 화면" })).toHaveAttribute("href", "/brokerage/orders?symbol=005930");
  });

  it("연동 전이면 실전투자 화면(연동 안내)으로 보낸다", () => {
    renderForm(false);
    expect(screen.getByRole("link", { name: "실전투자 화면" })).toHaveAttribute("href", "/brokerage");
    expect(screen.getByText(/증권사 계좌 연동 필요/)).toBeInTheDocument();
  });

  it("주문 전 리스크 체크 블록은 그대로 있다", () => {
    renderForm();
    expect(screen.getByText("주문 전 리스크 체크")).toBeInTheDocument();
  });
});
