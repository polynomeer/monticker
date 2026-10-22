import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

vi.mock("@/services/api", () => ({ authFetch: vi.fn() }));
vi.mock("@/hooks/usePaperTrade", () => ({ usePaperPortfolio: () => ({ data: { cash: 100_000, totalValue: 100_000, totalPnl: 0, totalPnlRate: 0, holdings: [] } }) }));

import { OrderForm } from "@/components/matching/OrderForm";

function renderForm() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OrderForm stockId={1} setStockId={() => {}} stocks={[{ id: 1, label: "삼성전자" }]} book={null} />
    </QueryClientProvider>,
  );
}

describe("체결 엔진 주문 — 가용 현금 초과는 입력 즉시 알린다", () => {
  it("매수 금액이 가용 현금을 넘으면 최대 수량과 함께 알리고 제출을 막는다", async () => {
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "지정가" }));
    await userEvent.type(screen.getByLabelText("지정가 (원)"), "30000");
    // 기본 수량 10주 × 30,000원 = 300,000원 > 100,000원

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("가용 현금 100,000원을 넘습니다 · 최대 3주");
    const qty = screen.getByLabelText("수량");
    expect(qty).toHaveAttribute("aria-invalid", "true");
    expect(qty).toHaveAttribute("aria-describedby", alert.id);
    expect(screen.getByRole("button", { name: "매수 주문 제출" })).toBeDisabled();
  });

  it("가용 현금 이내면 오류 없이 제출할 수 있다", async () => {
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "지정가" }));
    await userEvent.type(screen.getByLabelText("지정가 (원)"), "10000");

    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByLabelText("수량")).not.toHaveAttribute("aria-invalid");
    expect(screen.getByRole("button", { name: "매수 주문 제출" })).toBeEnabled();
  });

  it("매도에는 현금 한도를 걸지 않는다", async () => {
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "매도" }));
    await userEvent.click(screen.getByRole("button", { name: "지정가" }));
    await userEvent.type(screen.getByLabelText("지정가 (원)"), "30000");
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
