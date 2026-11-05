import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));
vi.mock("next/navigation", () => ({ usePathname: () => "/watchlist", useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ isLoggedIn: true, logout: vi.fn() }) }));
vi.mock("@/hooks/useConsents", () => ({ useConsentStatus: () => ({ data: undefined }) }));
vi.mock("@/components/terminal/StatusPill", () => ({ StatusPill: () => null }));

import { TerminalPage } from "@/components/terminal/Shell";

function renderShell() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><TerminalPage title="관심종목">본문</TerminalPage></QueryClientProvider>);
}

const meCalls = () => authFetch.mock.calls.filter((c) => c[0] === "/api/users/me").length;

describe("계정 메뉴 프로필", () => {
  beforeEach(() => {
    authFetch.mockReset();
    vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} unobserve() {} });
  });

  it("메뉴를 열 때만 읽고 닉네임·이메일을 보인다", async () => {
    authFetch.mockResolvedValue({ ok: true, json: async () => ({ id: 1, email: "demo@monticker.dev", nickname: "데모", provider: "LOCAL", emailVerified: true, createdAt: "" }) });
    renderShell();
    expect(meCalls()).toBe(0);
    await userEvent.click(screen.getByRole("button", { name: "내 계정" }));
    await waitFor(() => expect(screen.getByText("데모")).toBeInTheDocument());
    expect(screen.getByText("demo@monticker.dev")).toBeInTheDocument();
  });

  it("프로필을 못 읽어도 메뉴는 그대로 쓴다", async () => {
    authFetch.mockResolvedValue({ ok: false, json: async () => ({}) });
    renderShell();
    await userEvent.click(screen.getByRole("button", { name: "내 계정" }));
    expect(await screen.findByRole("button", { name: /로그아웃/ })).toBeInTheDocument();
    expect(screen.queryByText("demo@monticker.dev")).not.toBeInTheDocument();
  });
});
