import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

vi.mock("@/components/home/data", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/components/home/data")>()),
  useIsLoggedIn: () => true,
}));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ isLoggedIn: true }) }));
vi.mock("@/services/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/services/api")>()),
  authFetch: vi.fn(),
}));

import QuantSignals from "@/components/home/QuantSignals";
import PositionsPanel from "@/components/stock/PositionsPanel";
import { authFetch } from "@/services/api";

function wrap(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe("홈 퀀트 시그널 — 실패를 '없음'으로 보이지 않는다", () => {
  beforeEach(() => vi.mocked(authFetch).mockReset());

  it("조회 실패는 '시그널 없음'이 아니라 조회 실패로 보인다", async () => {
    vi.mocked(authFetch).mockResolvedValue({ ok: false, status: 500 } as Response);
    wrap(<QuantSignals />);
    expect(await screen.findByText(/퀀트 시그널을\(를\) 불러오지 못했습니다/)).toBeTruthy();
    expect(screen.queryByText("아직 표시할 시그널이 없습니다")).toBeNull();
  });

  it("정말 비어 있으면 안내를 보인다", async () => {
    vi.mocked(authFetch).mockResolvedValue({ ok: true, json: async () => [] } as unknown as Response);
    wrap(<QuantSignals />);
    expect(await screen.findByText("아직 표시할 시그널이 없습니다")).toBeTruthy();
  });
});

describe("종목 화면 체결 내역 — 실패를 '없음'으로 보이지 않는다", () => {
  const panel = (
    <PositionsPanel
      symbol="005930" stockId={1} brokerageConnected={false} activeOrders={[]} onCancelOrder={() => {}}
      cancelPending={false} paperOrders={[]} onCancelPaperOrder={() => {}} paperCancelPending={false}
    />
  );
  const openFills = async () => fireEvent.click(await screen.findByText("체결 내역"));

  beforeEach(() => vi.mocked(authFetch).mockReset());

  it("조회 실패는 빈 표가 아니라 조회 실패로 보인다", async () => {
    vi.mocked(authFetch).mockResolvedValue({ ok: false, status: 500 } as Response);
    wrap(panel);
    await openFills();
    expect(await screen.findByText(/체결 내역을\(를\) 불러오지 못했습니다/)).toBeTruthy();
  });

  it("정말 비어 있으면 실패 문구를 보이지 않는다", async () => {
    vi.mocked(authFetch).mockResolvedValue({ ok: true, json: async () => [] } as unknown as Response);
    wrap(panel);
    await openFills();
    expect(await screen.findByText(/모의투자 체결/)).toBeTruthy();
    expect(screen.queryByText(/불러오지 못했습니다/)).toBeNull();
  });
});
