import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const replace = vi.fn();
let search = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => "/assets",
}));
// 셸(상단 바·레일)은 이 테스트의 관심이 아니다
vi.mock("@/components/terminal/Shell", () => ({
  TerminalPage: ({ children, stats }: { children: React.ReactNode; stats?: { label: string }[] }) => (
    <div><div data-testid="stats">{(stats ?? []).map((s) => s.label).join(",")}</div>{children}</div>
  ),
}));
vi.mock("@/components/home/data", () => ({ useIsLoggedIn: () => true, eventLabel: (t: string) => t }));
vi.mock("@/hooks/useConsents", () => ({ useConsentStatus: () => ({ data: undefined }) }));
const accounts = [{ id: 1, source: "CSV", institution: "KIWOOM", name: "키움 CSV", accountKind: "GENERAL", accountRefMasked: null, createdAt: "" }];
vi.mock("@/hooks/usePortfolio", () => ({
  useAccounts: () => ({ data: accounts, isPending: false, isError: false }),
  useHoldings: () => ({ data: { scope: "REAL", holdings: [], totals: [] }, isPending: false }),
  useAgreePortfolioConsent: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
}));
vi.mock("@/components/portfolio/PaperPortfolioView", () => ({
  PaperPortfolioView: () => <div>모의 포트폴리오 본문</div>,
  usePaperStats: () => [{ label: "총 평가금액" }],
}));
vi.mock("@/components/assets/HoldingEventsFeed", () => ({ HoldingEventsFeed: () => null }));
vi.mock("@/components/assets/AccountDetail", () => ({ AccountDetail: ({ account }: { account: { name: string } }) => <div>상세 {account.name}</div> }));
vi.mock("@/components/assets/DeleteAllData", () => ({ DeleteAllData: () => null }));
let brokerage: unknown = null;
vi.mock("@/hooks/useBrokerage", () => ({ useBrokerageAccount: () => ({ data: brokerage }) }));

import AssetsPage from "@/app/assets/page";

describe("내 자산 — 탭은 주소에 남는다(ADR-110)", () => {
  beforeEach(() => { replace.mockReset(); search = ""; brokerage = null; });

  it("view=PAPER면 모의 포트폴리오 전체와 모의 계좌 지표를 보여 준다", () => {
    search = "view=PAPER";
    render(<AssetsPage />);
    expect(screen.getByText("모의 포트폴리오 본문")).toBeInTheDocument();
    expect(screen.getByTestId("stats")).toHaveTextContent("총 평가금액");
  });

  it("탭을 바꾸면 주소를 바꾸고, 통합은 기본 주소다", async () => {
    render(<AssetsPage />);
    await userEvent.click(screen.getByRole("button", { name: "계좌별" }));
    expect(replace).toHaveBeenCalledWith("/assets?view=ACCOUNTS", { scroll: false });
    await userEvent.click(screen.getByRole("button", { name: "통합" }));
    expect(replace).toHaveBeenLastCalledWith("/assets", { scroll: false });
  });

  it("모르는 view 값은 통합으로 본다", () => {
    search = "view=evil";
    render(<AssetsPage />);
    expect(screen.queryByText("모의 포트폴리오 본문")).not.toBeInTheDocument();
    expect(screen.getByText("보유 종목이 없습니다.")).toBeInTheDocument();
  });

  it("계좌별에 주문용 증권사 연동이 있으면 실전투자로 가는 카드를 두고, 잔고는 합치지 않는다고 밝힌다", () => {
    search = "view=ACCOUNTS";
    brokerage = { id: 9, provider: "KIS", accountNumber: "12345678-01", accountType: "", isActive: true, connectedAt: "", tokenValid: true };
    render(<AssetsPage />);
    expect(screen.getByText("상세 키움 CSV")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "실전투자로" })).toHaveAttribute("href", "/brokerage");
    expect(screen.getByText(/통합 합계에 아직 넣지 않습니다/)).toBeInTheDocument();
  });
});
