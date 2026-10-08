import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

const authFetch = vi.fn();
const getBrokerageAccount = vi.fn();
const getBrokerageBalance = vi.fn();

vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));
vi.mock("@/services/brokerage", () => ({
  getBrokerageAccount: () => getBrokerageAccount(),
  getBrokerageBalance: () => getBrokerageBalance(),
}));

import { chipAmount, useAccountChipSummary } from "@/hooks/useAccountSummary";

function wrap(children: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function Chip({ kind, loggedIn = true }: { kind: "paper" | "live"; loggedIn?: boolean }) {
  const s = useAccountChipSummary(kind, loggedIn);
  return <span data-testid="chip">{s.amount === undefined ? "(없음)" : `${s.amountLabel}:${s.amount}`}</span>;
}

describe("chipAmount", () => {
  it("원 단위 천 단위 구분", () => {
    expect(chipAmount({ data: 10_234_567 })).toBe("10,234,567원");
    expect(chipAmount({ data: 0 })).toBe("0원");
  });

  it("로딩·오류·비정상 값이면 지어내지 않고 —", () => {
    expect(chipAmount({ data: undefined })).toBe("—");
    expect(chipAmount({ data: null })).toBe("—");
    expect(chipAmount({ data: 5000, isError: true })).toBe("—");
    expect(chipAmount({ data: Number.NaN })).toBe("—");
  });
});

describe("useAccountChipSummary", () => {
  beforeEach(() => {
    authFetch.mockReset();
    getBrokerageAccount.mockReset();
    getBrokerageBalance.mockReset();
  });

  it("모의: 불러오는 동안 —, 응답 후 총자산", async () => {
    authFetch.mockResolvedValue({ ok: true, json: async () => ({ totalAssets: 12_345_000 }) });
    render(wrap(<Chip kind="paper" />));
    expect(screen.getByTestId("chip")).toHaveTextContent("모의 총자산:—");
    await waitFor(() => expect(screen.getByTestId("chip")).toHaveTextContent("모의 총자산:12,345,000원"));
    expect(authFetch).toHaveBeenCalledWith("/api/wallet");
    expect(getBrokerageAccount).not.toHaveBeenCalled();
  });

  it("모의: 조회 실패면 —", async () => {
    authFetch.mockResolvedValue({ ok: false, json: async () => ({}) });
    render(wrap(<Chip kind="paper" />));
    await waitFor(() => expect(authFetch).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 10));
    expect(screen.getByTestId("chip")).toHaveTextContent("모의 총자산:—");
  });

  it("로그아웃이면 금액을 그리지 않고 조회도 하지 않는다", () => {
    render(wrap(<Chip kind="paper" loggedIn={false} />));
    expect(screen.getByTestId("chip")).toHaveTextContent("(없음)");
    expect(authFetch).not.toHaveBeenCalled();
  });

  it("실전: 연동된 계좌가 있으면 가용 현금", async () => {
    getBrokerageAccount.mockResolvedValue({ id: 1, isActive: true });
    getBrokerageBalance.mockResolvedValue({ cash: 3_000_000, totalEvaluated: 9_000_000, holdings: [] });
    render(wrap(<Chip kind="live" />));
    await waitFor(() => expect(screen.getByTestId("chip")).toHaveTextContent("실계좌 가용 현금:3,000,000원"));
    expect(authFetch).not.toHaveBeenCalled();
  });

  it("실전: 미연동이면 금액을 그리지 않고 잔고도 조회하지 않는다", async () => {
    getBrokerageAccount.mockResolvedValue(null);
    render(wrap(<Chip kind="live" />));
    await waitFor(() => expect(screen.getByTestId("chip")).toHaveTextContent("(없음)"));
    expect(getBrokerageBalance).not.toHaveBeenCalled();
  });

  it("실전: 잔고 조회가 실패하면 —", async () => {
    getBrokerageAccount.mockResolvedValue({ id: 1, isActive: true });
    getBrokerageBalance.mockRejectedValue(new Error("broker down"));
    render(wrap(<Chip kind="live" />));
    await waitFor(() => expect(getBrokerageBalance).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 10));
    expect(screen.getByTestId("chip")).toHaveTextContent("실계좌 가용 현금:—");
  });
});
