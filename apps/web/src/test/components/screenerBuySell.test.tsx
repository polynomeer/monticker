import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import ScreenerRow from "@/components/screener/ScreenerRow";
import { columnsFor } from "@/components/screener/ScreenerTable";
import type { ScreenerItem } from "@/hooks/useScreener";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

const item = {
  rank: 1, stockId: 2, symbol: "005930", name: "삼성전자", market: "KOSPI", sector: "전기전자",
  price: 70000, changeRate: 1.2, changeAmount: 830, volume: 1000, amount: 70_000_000,
  buyRatio: null, sellRatio: null, marketCap: null, per: null, pbr: null, isFundamentalsMocked: false,
} as unknown as ScreenerItem;

describe("스크리너 매수/매도 비율 — 실제 수급 소스가 없으면 지어내지 않는다", () => {
  it("열 제목에 준비 중을 붙인다", () => {
    expect(columnsFor("trade").find((c) => c.key === "buySell")?.label).toContain("준비 중");
  });

  it("서버가 null을 주면 막대·숫자 대신 —", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ScreenerRow item={item} columns={columnsFor("trade").filter((c) => c.key === "buySell")} />
      </QueryClientProvider>,
    );

    expect(screen.queryByText("/")).toBeNull();          // "55 / 45" 같은 비율 표기 없음
    expect(screen.getAllByText(/^[—-]$/).length).toBeGreaterThan(0);
  });
});
