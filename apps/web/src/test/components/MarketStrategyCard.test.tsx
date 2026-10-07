import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { MarketStrategy } from "@monticker/types";
import { MarketStrategyCard } from "@/components/strategy-market/MarketStrategyCard";

vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

// 보안 리뷰 — 마켓 목록은 작성자 이메일을 내보내지 않는다. 카드는 서버가 주는 닉네임을 그대로 보여 준다.
const strategy: MarketStrategy = {
  id: 1, ruleset_id: "rs1", name: "모멘텀", description: null, price: 0, subscribe_count: 3,
  author_nickname: "퀀트곰", created_at: "2026-10-01T00:00:00Z", isSubscribed: false, performance: null,
};

describe("MarketStrategyCard", () => {
  it("작성자를 닉네임으로 표시한다", () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={qc}><MarketStrategyCard strategy={strategy} /></QueryClientProvider>);
    expect(screen.getByText(/by 퀀트곰/)).toBeInTheDocument();
  });
});
