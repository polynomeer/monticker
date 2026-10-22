import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { DividendSummary, KrwSummary, PortfolioAnalysis } from "@monticker/types";
import { AllocationPanel, DividendsPanel, KrwSummaryTiles, ReturnsPanel } from "@/components/assets/AnalysisPanels";

const krw: KrwSummary = {
  marketValueKrw: 1220000, costBasisKrw: 960100, unrealizedPnlKrw: 259900, pricePnlKrw: 239900, fxPnlKrw: 20000,
  excludedCurrencies: ["JPY"], unpricedCount: 1, fxMocked: true,
};

const months = Array.from({ length: 12 }, (_, i) => ({ month: `2026-${String(i + 1).padStart(2, "0")}`, byCurrency: {} as Record<string, number>, krw: 0 as number | null }));
months[3] = { month: "2026-04", byCurrency: { KRW: 846 }, krw: 846 };
months[4] = { month: "2026-05", byCurrency: { USD: 0.85 }, krw: 1147.5 };
const dividends: DividendSummary = { trailing12mKrw: 1993.5, yieldOnCost: 0.2076, months, topStocks: [{ stockId: 2, instrumentCode: null, name: "미국B", currency: "USD", trailing12m: 0.85 }] };

describe("분석 패널(ADR-111)", () => {
  it("원화 요약은 주가·환차손익을 나누고, 모의 환율과 빠진 통화를 밝힌다", () => {
    render(<KrwSummaryTiles krw={krw} fxRate={{ krwRate: 1400, mocked: true }} />);
    expect(screen.getByText("모의 환율")).toBeInTheDocument();
    expect(screen.getByText("+239,900원")).toBeInTheDocument();
    expect(screen.getByText("+20,000원")).toBeInTheDocument();
    expect(screen.getByText("JPY")).toBeInTheDocument();
    expect(screen.getByText("1,400원 (모의)")).toBeInTheDocument();
  });

  it("환차손익을 모르면 그 이유를 적는다", () => {
    render(<KrwSummaryTiles krw={{ ...krw, costBasisKrw: null, unrealizedPnlKrw: null, fxPnlKrw: null, fxMocked: false, excludedCurrencies: [], unpricedCount: 0 }} />);
    expect(screen.getByText(/매수한 날의 환율 기록이 없는 종목/)).toBeInTheDocument();
  });

  it("배분은 차원을 바꾸고 키를 사람이 읽는 이름으로 보인다", async () => {
    const analysis = {
      krw, fx: [], dividends, returns: [],
      allocations: [
        { dimension: "SECTOR", slices: [{ key: "UNCLASSIFIED", valueKrw: 100, weight: 100 }] },
        { dimension: "ACCOUNT_KIND", slices: [{ key: "ISA", valueKrw: 60, weight: 60 }, { key: "PENSION", valueKrw: 40, weight: 40 }] },
      ],
    } as unknown as PortfolioAnalysis;
    render(<AllocationPanel analysis={analysis} />);
    expect(screen.getByText("미분류")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "계좌 종류" }));
    expect(screen.getByText("연금저축")).toBeInTheDocument();
    expect(screen.getByText("60.0%")).toBeInTheDocument();
  });

  it("배당 막대에 올리면 그 달의 통화별 금액과 원화를, 표로도 보인다", async () => {
    render(<DividendsPanel dividends={dividends} />);
    fireEvent.mouseEnter(screen.getByRole("listitem", { name: /2026-05/ }));
    expect(screen.getByText("2026-05 · 0.85 USD = 1,148원")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "표로 보기" }));
    expect(screen.getByRole("table")).toHaveTextContent("846원");
  });

  it("수익률을 못 내면 이유를 보인다", () => {
    render(<ReturnsPanel returns={[{ currency: "JPY", xirr: null, invested: 3000, returned: 0, currentValue: 0, multiple: null, since: "2025-06-02", reason: "시세가 없는 종목이 있어 계산하지 않습니다" }]} />);
    expect(screen.getByText("시세가 없는 종목이 있어 계산하지 않습니다")).toBeInTheDocument();
  });
});
