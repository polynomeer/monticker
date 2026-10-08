import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { UpdateWatchRuleRequest, WatchRuleResponse } from "@monticker/types";
import { ApiError } from "@/services/brokerage";

vi.mock("next/link", () => ({ default: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a> }));
vi.mock("@/services/auth", () => ({ getAccessToken: () => "tok" }));
vi.mock("@/services/api", () => ({ authFetch: (input: string, init?: RequestInit) => fetch(input, init) }));
vi.mock("@/hooks/usePaperTrade", () => ({ usePaperPnlByOrigin: () => ({ data: undefined }) }));
// 셸(내비게이션·계좌 칩)은 이 테스트의 관심사가 아니다
vi.mock("@/components/terminal", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/components/terminal")>()),
  TerminalPage: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));

let rules: WatchRuleResponse[] = [];
const updateWatchRule = vi.fn<(id: number, req: UpdateWatchRuleRequest) => Promise<WatchRuleResponse>>();
vi.mock("@/services/watchrule", () => ({
  getWatchRules: () => Promise.resolve(rules),
  getWatchRuleExecutions: () => Promise.resolve([]),
  createWatchRule: vi.fn(),
  deleteWatchRule: vi.fn(),
  updateWatchRule: (id: number, req: UpdateWatchRuleRequest) => updateWatchRule(id, req),
}));

import WatchRulesPage from "@/app/watch-rules/page";

const RULE: WatchRuleResponse = {
  id: 5, stockId: 19, eventType: "VOLUME_SURGE", side: "BUY", quantity: 10, minImportanceScore: 0,
  cooldownSec: 3600, isActive: true, createdAt: "2026-10-01T00:00:00Z", name: "거래량", dailyLimit: null,
  targetType: "STOCK", targetGroupId: null, orderType: "MARKET", limitOffsetBps: null, sizeType: "SHARES", equityPct: null,
};

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  rules = [RULE];
  updateWatchRule.mockReset();
  global.fetch = vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    const body = url === "/api/stocks/19" ? { id: 19, symbol: "035720", name: "카카오" }
      : url.startsWith("/api/watchlists") ? [{ id: 4, name: "반도체", items: [{}] }]
      : [];
    return Promise.resolve({ ok: true, json: () => Promise.resolve(body) } as Response);
  }) as typeof fetch;
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><WatchRulesPage /></QueryClientProvider>);
}

async function openEdit(user: ReturnType<typeof userEvent.setup>) {
  renderPage();
  const edit = await screen.findByRole("button", { name: /^거래량 · .* 수정$/ });
  await user.click(edit);
  await screen.findByText("규칙 수정");
  return edit;
}

const ruleRow = () => screen.getAllByRole("listitem").find((li) => li.textContent?.includes("거래량 ·"))!;

describe("/watch-rules 규칙 수정", () => {
  it("수정 버튼이 같은 폼을 규칙 값으로 열고, 첫 입력칸에 포커스한다", async () => {
    const user = userEvent.setup();
    await openEdit(user);
    expect(screen.getByLabelText("규칙 이름")).toHaveValue("거래량");
    expect(screen.getByLabelText("규칙 이름")).toHaveFocus();
    expect(screen.getByLabelText("쿨다운")).toHaveValue("3600");
  });

  it("쿨다운만 바꾸면 목록에 먼저 반영하고, 성공하면 폼을 닫고 수정 버튼으로 포커스를 돌려준다", async () => {
    const user = userEvent.setup();
    const pending = deferred<WatchRuleResponse>();
    updateWatchRule.mockReturnValue(pending.promise);
    const editBtn = await openEdit(user);

    await user.selectOptions(screen.getByLabelText("쿨다운"), "0");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(updateWatchRule).toHaveBeenCalledWith(5, { cooldownSec: 0 });
    await waitFor(() => expect(within(ruleRow()).getByText(/쿨다운 없음/)).toBeInTheDocument());

    rules = [{ ...RULE, cooldownSec: 0 }];
    pending.resolve(rules[0]);
    await waitFor(() => expect(screen.queryByText("규칙 수정")).not.toBeInTheDocument());
    expect(screen.getByText("새 규칙")).toBeInTheDocument();
    await waitFor(() => expect(editBtn).toHaveFocus());
  });

  it("서버가 거부하면 낙관적 반영을 되돌리고 메시지를 폼 안에 보인다", async () => {
    const user = userEvent.setup();
    updateWatchRule.mockRejectedValue(new ApiError(400, "쿨다운은 0 이상이어야 합니다", null));
    await openEdit(user);

    await user.selectOptions(screen.getByLabelText("쿨다운"), "0");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("쿨다운은 0 이상이어야 합니다");
    expect(within(ruleRow()).getByText(/쿨다운 1시간/)).toBeInTheDocument();
    expect(screen.getByText("규칙 수정")).toBeInTheDocument();
  });

  it("대상 변경은 낙관적으로 반영하지 않고, 404 메시지를 폼 안에 보인다", async () => {
    const user = userEvent.setup();
    const pending = deferred<WatchRuleResponse>();
    updateWatchRule.mockReturnValue(pending.promise);
    await openEdit(user);

    await user.click(screen.getByRole("button", { name: "관심종목 그룹" }));
    await waitFor(() => expect(screen.getByRole("option", { name: "반도체 · 1종목" })).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(updateWatchRule).toHaveBeenCalledWith(5, { targetType: "GROUP", targetGroupId: 4 });
    // 응답 전에는 목록이 그대로다
    expect(within(ruleRow()).queryByText("관심종목 그룹")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "저장 중..." })).toBeDisabled();

    pending.reject(new ApiError(404, "관심종목 그룹을 찾을 수 없습니다: 4", null));
    expect(await screen.findByRole("alert")).toHaveTextContent("관심종목 그룹을 찾을 수 없습니다: 4");
  });

  it("Esc로 닫으면 새 규칙 폼으로 돌아간다", async () => {
    const user = userEvent.setup();
    await openEdit(user);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByText("규칙 수정")).not.toBeInTheDocument());
    expect(updateWatchRule).not.toHaveBeenCalled();
  });
});
