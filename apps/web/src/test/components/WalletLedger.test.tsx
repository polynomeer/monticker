import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import WalletLedger from "@/components/wallet/WalletLedger";

// ADR-043 — 원장은 {items, nextCursor} 커서 페이지다. 컴포넌트는 nextCursor를 다음 요청의 cursor로
// 넘겨야 하고, null이면 더 요청하지 않아야 한다. authFetch는 실제 fetch 경계이므로 fetch를 직접 잡는다.
const mockFetch = vi.fn();
global.fetch = mockFetch;

type Cb = (entries: { isIntersecting: boolean }[]) => void;
let observerCallback: Cb | null = null;
class FakeObserver {
  constructor(cb: Cb) { observerCallback = cb; }
  observe() {}
  disconnect() { observerCallback = null; }
}

function ev(id: number) {
  return { id, eventType: "FILL", amount: -1000 * id, balanceAfter: 9_000_000, paperTradeId: null, stockId: null, description: `체결 ${id}`, createdAt: "2026-09-13T01:00:00Z" };
}
function page(items: unknown[], nextCursor: number | null) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ items, nextCursor }) } as Response);
}
function renderLedger() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><WalletLedger /></QueryClientProvider>);
}
const requestedUrls = () => mockFetch.mock.calls.map(c => String(c[0]));

beforeEach(() => {
  mockFetch.mockReset();
  localStorage.setItem("accessToken", "token");
  vi.stubGlobal("IntersectionObserver", FakeObserver);
});
afterEach(() => { vi.unstubAllGlobals(); observerCallback = null; });

describe("WalletLedger", () => {
  it("첫 페이지는 cursor 없이 요청하고, 센티널이 보이면 nextCursor로 다음 페이지를 요청한다", async () => {
    mockFetch
      .mockImplementationOnce(() => page([ev(30), ev(29)], 29))
      .mockImplementationOnce(() => page([ev(28)], null));

    renderLedger();
    await screen.findByText("체결 30");
    expect(requestedUrls()[0]).toBe("/api/wallet/ledger?limit=20");

    await act(async () => { observerCallback?.([{ isIntersecting: true }]); });

    await screen.findByText("체결 28");
    expect(requestedUrls()[1]).toBe("/api/wallet/ledger?limit=20&cursor=29");
    expect(screen.getAllByText(/체결 \d+/)).toHaveLength(3);
  });

  it("nextCursor가 null이면 센티널이 보여도 더 요청하지 않는다", async () => {
    mockFetch.mockImplementationOnce(() => page([ev(1)], null));

    renderLedger();
    await screen.findByText("체결 1");

    await act(async () => { observerCallback?.([{ isIntersecting: true }]); });
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1));
  });

  it("원장이 비어 있으면 안내 문구를 보여 준다", async () => {
    mockFetch.mockImplementationOnce(() => page([], null));

    renderLedger();
    expect(await screen.findByText(/아직 거래 기록이 없습니다/)).toBeInTheDocument();
  });
});
