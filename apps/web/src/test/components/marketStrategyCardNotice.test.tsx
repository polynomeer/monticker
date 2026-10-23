import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { MarketStrategy } from "@monticker/types";

vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

import { MarketStrategyCard } from "@/components/strategy-market/MarketStrategyCard";

const base: MarketStrategy = {
  id: 1, ruleset_id: "rs1", name: "모멘텀", description: null, price: 0, subscribe_count: 3,
  author_nickname: "퀀트곰", created_at: "2026-10-01T00:00:00Z", isSubscribed: false, performance: null,
};

function renderCard(s: MarketStrategy) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><MarketStrategyCard strategy={s} /></QueryClientProvider>);
}

describe("MarketStrategyCard — 성과 고지·검증 전 표시 (약관 제9조)", () => {
  it("카드마다 과거 성과 고지를 붙인다", () => {
    renderCard(base);
    expect(screen.getByText(/과거 성과는 미래 수익을 보장하지 않습니다/)).toBeInTheDocument();
  });

  it("검증 전 카드는 표본(포워드 주)을 배지에 적고 숫자를 흐리게 한다", () => {
    const started = new Date(Date.now() - 3 * 7 * 86400_000 - 3600_000).toISOString();
    renderCard({
      ...base,
      performance: {
        backtest: null,
        forward: { status: "RUNNING", startedAt: started, stoppedAt: null, matchRate: 0.9, matchedSignals: 9, comparedSignals: 10 },
      },
    });
    expect(screen.getByText("검증 전 · 표본 3주")).toBeInTheDocument();
    expect(screen.getByTestId("strategy-metrics")).toHaveClass("opacity-60");
  });

  it("포워드 기록이 없으면 표본 0주", () => {
    renderCard(base);
    expect(screen.getByText("검증 전 · 표본 0주")).toBeInTheDocument();
  });

  it("지표는 MDD → 포워드 기간 → CAGR 순이고 백테스트/포워드를 이름에 밝힌다", () => {
    renderCard(base);
    const labels = within(screen.getByTestId("strategy-metrics")).getAllByText(/MDD|포워드|CAGR/).map((e) => e.textContent);
    expect(labels).toEqual(["MDD · 백테스트", "포워드 기간", "포워드 기록 없음", "CAGR · 백테스트"]);
  });
});
