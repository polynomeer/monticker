import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { RuleSet } from "@monticker/types";
import { VersionPanel } from "@/components/quant/VersionPanel";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));

const rsi = { indicator: "RSI", comparator: "LT", params: { period: 14 }, value: 30 };
const vol = { indicator: "VOLUME_RATIO", comparator: "GT", params: { period: 20 }, value: 2 };
const def = (entry: object[]) => ({ entryRules: { operator: "AND", conditions: entry }, exitRules: { operator: "OR", conditions: [] }, positionSizing: { value: 10 } });

const ruleset = (status = "BACKTESTED"): RuleSet => ({
  id: "rs1", name: "모멘텀", description: null, version: 2, status,
  ruleDefinition: JSON.stringify(def([rsi])), createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-02T00:00:00Z",
});

function renderPanel(rs: RuleSet, onRestore = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <VersionPanel ruleset={rs} onRestore={onRestore} restoreBlockedReason={rs.status === "RUNNING" ? "운용 중" : null} />
    </QueryClientProvider>,
  );
  return onRestore;
}

describe("VersionPanel — 룰셋 빌더 버전 탭", () => {
  beforeEach(() => {
    authFetch.mockReset();
    authFetch.mockResolvedValue({
      ok: true,
      json: async () => [{ version: 1, ruleDefinition: def([rsi, vol]), fingerprint: "f1", changeSummary: null, createdAt: "2026-10-01T00:00:00Z" }],
    });
  });

  it("기존 버전 조회 API로 이력을 불러와 현재 버전과 함께 보여 준다", async () => {
    renderPanel(ruleset());
    expect(await screen.findByText("v1")).toBeInTheDocument();
    expect(authFetch).toHaveBeenCalledWith("/api/quant/rulesets/rs1/versions");
    expect(screen.getByText("v2")).toBeInTheDocument();
    expect(screen.getByText("현재")).toBeInTheDocument();
    expect(screen.getByText("매수 조건 −1")).toBeInTheDocument();
    expect(screen.getByText("최초 버전")).toBeInTheDocument();
  });

  it("지난 버전을 고르면 룰을 읽기 전용으로 보여 주고, 불러오기는 콜백만 부른다(서버 호출 없음)", async () => {
    const onRestore = renderPanel(ruleset());
    fireEvent.click((await screen.findByText("v1")).closest("button")!);

    const detail = screen.getByLabelText("v1 룰 (읽기 전용)");
    expect(within(detail).getByText(/RSI\(14\)/)).toBeInTheDocument();
    expect(within(detail).getByText(/거래량 배율\(20\)/)).toBeInTheDocument();
    expect(detail.querySelector("input, select, textarea")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /v1 룰을 빌더로 불러오기/ }));
    expect(onRestore).toHaveBeenCalledWith(expect.objectContaining({ version: 1 }));
    expect(authFetch).toHaveBeenCalledTimes(1);
  });

  it("현재 버전에는 불러오기 버튼이 없다", async () => {
    renderPanel(ruleset());
    await screen.findByText("v1");
    fireEvent.click(screen.getByText("v2").closest("button")!);
    expect(screen.queryByRole("button", { name: /불러오기/ })).toBeNull();
  });

  it("포워드 테스트 운용 중이면 불러오기를 막는다", async () => {
    renderPanel(ruleset("RUNNING"));
    fireEvent.click((await screen.findByText("v1")).closest("button")!);
    expect(screen.getByRole("button", { name: /불러오기/ })).toBeDisabled();
    expect(screen.getByText("운용 중")).toBeInTheDocument();
  });
});
