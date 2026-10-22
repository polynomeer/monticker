import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { RiskCheckResult } from "@/hooks/useRiskPreview";

const buyAsync = vi.fn();
const preview: { data?: RiskCheckResult } = {};
vi.mock("@/services/api", () => ({ authFetch: vi.fn() }));
vi.mock("@/hooks/usePaperTrade", () => ({
  usePaperTrade: () => ({ buy: { mutateAsync: buyAsync, isPending: false }, sell: { mutateAsync: vi.fn(), isPending: false } }),
}));
vi.mock("@/hooks/useRiskPreview", () => ({
  useRiskPreview: () => ({ data: preview.data, error: null, pending: false, idle: !preview.data }),
}));

import { QuickTradePanel } from "@/components/portfolio/QuickTradePanel";

const portfolio = { cash: 10_000_000, totalValue: 10_000_000, totalPnl: 0, totalPnlRate: 0, holdings: [] };

async function pickStock() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={qc}><QuickTradePanel portfolio={portfolio} meta={new Map()} sectorValue={new Map()} /></QueryClientProvider>);
  await userEvent.type(screen.getByLabelText("종목 매수 검색"), "삼성");
  await userEvent.click(await screen.findByRole("button", { name: /삼성전자/ }));
  await screen.findByRole("button", { name: "변경" });
}

describe("빠른 매수 — 리스크 미리보기로 막는다", () => {
  beforeEach(() => {
    buyAsync.mockReset();
    delete preview.data;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => ({
      ok: true,
      json: async () => (url.includes("/search") ? [{ id: 9, symbol: "005930", name: "삼성전자" }] : { price: 70000 }),
    })));
  });

  it("한도에 걸리면 제출을 막고 이유와 리스크 한도 링크를 보여 준다", async () => {
    preview.data = {
      approved: false, blockedBy: "ConcentrationRule", severity: "BLOCKED",
      checks: [{ rule: "ConcentrationRule", passed: false, detail: "집중도 42.00% / 한도 30%", current: 42, limit: 30 }],
    };
    await pickStock();
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("단일 종목 집중도 한도에 걸립니다 · 집중도 42.00% / 한도 30%");
    expect(screen.getByRole("link", { name: "리스크 한도 보기" })).toHaveAttribute("href", "/risk");
    const btn = screen.getByRole("button", { name: "모의 매수" });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute("aria-disabled", "true");
  });

  it("통과하면 제출할 수 있다", async () => {
    preview.data = { approved: true, blockedBy: null, severity: "APPROVED", checks: [] };
    await pickStock();
    expect(screen.queryByRole("alert")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "모의 매수" }));
    expect(buyAsync).toHaveBeenCalledWith({ stockId: 9, quantity: 1 });
  });
});
