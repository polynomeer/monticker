import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { WatchRuleResponse } from "@monticker/types";
import { WatchRuleForm } from "@/components/watchrule/WatchRuleForm";

vi.mock("@/services/api", () => ({
  authFetch: (input: string, init?: RequestInit) => fetch(input, init),
}));

const mockFetch = vi.fn();
global.fetch = mockFetch;

const ok = (data: unknown) => Promise.resolve({ ok: true, json: () => Promise.resolve(data) } as Response);

const GROUPS = [{ id: 4, name: "반도체", items: [{}, {}] }, { id: 9, name: "2차전지", items: [] }];

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockImplementation((url: string) =>
    ok(String(url).startsWith("/api/watchlists") ? GROUPS : [{ id: 42, symbol: "005930", name: "삼성전자" }]),
  );
});

const rule: WatchRuleResponse = {
  id: 5, stockId: 19, eventType: "PRICE_SPIKE", side: "SELL", quantity: 7, minImportanceScore: 80,
  cooldownSec: 3600, isActive: true, createdAt: "2026-10-01T00:00:00Z", name: "급등 매도", dailyLimit: 3,
  requiredEventTypes: ["VOLUME_SURGE"], conditionWindowSec: 1800,
  targetType: "STOCK", targetGroupId: null, orderType: "LIMIT", limitOffsetBps: 50, sizeType: "SHARES", equityPct: null,
};

function renderEdit(r: WatchRuleResponse = rule, extra: { serverError?: string | null; submitting?: boolean } = {}) {
  const onUpdate = vi.fn();
  const onCancel = vi.fn();
  const utils = render(
    <WatchRuleForm
      rule={r}
      initialStock={r.stockId === 19 ? { id: 19, symbol: "035720", name: "카카오" } : null}
      onUpdate={onUpdate}
      onCancel={onCancel}
      submitting={extra.submitting ?? false}
      serverError={extra.serverError}
    />,
  );
  return { onUpdate, onCancel, ...utils };
}

describe("WatchRuleForm 수정 모드 (ADR-098)", () => {
  it("규칙 값으로 채운다 — 바꿀 수 없는 감지 조건·매수/매도는 읽기 전용", () => {
    renderEdit();
    expect(screen.getByLabelText("규칙 이름")).toHaveValue("급등 매도");
    expect(screen.getByLabelText("중요도 하한")).toHaveValue(80);
    expect(screen.getByText("이벤트 · 가격 급등 + 거래량 급증")).toBeInTheDocument();
    expect(screen.queryByLabelText("감지할 이벤트")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "매수" })).not.toBeInTheDocument();
    expect(screen.getByText("매도")).toBeInTheDocument();
    expect(screen.getByText("카카오")).toBeInTheDocument();
    expect(screen.getByLabelText("주문 유형")).toHaveValue("LIMIT");
    expect(screen.getByLabelText("지정가 오프셋 (bp)")).toHaveValue(50);
    expect(screen.getByLabelText("수량 기준")).toHaveValue("SHARES");
    expect(screen.getByLabelText("수량 (주)")).toHaveValue(7);
    expect(screen.getByLabelText("하루 최대 발동")).toHaveValue(3);
    expect(screen.getByLabelText("쿨다운")).toHaveValue("3600");
    expect(screen.getByRole("button", { name: "변경 저장" })).toBeEnabled();
  });

  it("규칙에 없는 값은 지어내지 않는다 — 수량 기준을 바꾸면 비율 칸이 비어 있고, 비우면 제출되지 않는다", async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderEdit();
    await user.selectOptions(screen.getByLabelText("수량 기준"), "EQUITY_PCT");
    expect(screen.getByLabelText("계좌 비율 (%)")).toHaveValue(null);
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(onUpdate).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("1~25%");
  });

  it("목록에 없는 쿨다운 값(API로 만든 규칙)은 그대로 고를 수 있게 둔다", () => {
    renderEdit({ ...rule, cooldownSec: 90 });
    expect(screen.getByLabelText("쿨다운")).toHaveValue("90");
    expect(screen.getByRole("option", { name: "90초 (현재 값)" })).toBeInTheDocument();
  });

  it("PATCH 본문에는 바뀐 값만 담는다", async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderEdit();
    await user.clear(screen.getByLabelText("수량 (주)"));
    await user.type(screen.getByLabelText("수량 (주)"), "12");
    await user.selectOptions(screen.getByLabelText("쿨다운"), "0");
    await user.clear(screen.getByLabelText("하루 최대 발동"));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(onUpdate).toHaveBeenCalledWith({ quantity: 12, cooldownSec: 0, dailyLimit: 0 });
  });

  it("바뀐 것이 없으면 보내지 않고 알려준다", async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderEdit();
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(onUpdate).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("바뀐 내용이 없습니다");
  });

  it("생성과 같은 범위로 막는다 — 오프셋 ±1000bp 초과", async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderEdit();
    await user.clear(screen.getByLabelText("지정가 오프셋 (bp)"));
    await user.type(screen.getByLabelText("지정가 오프셋 (bp)"), "-1001");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(onUpdate).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("1000bp");
  });

  it("대상을 종목에서 그룹으로 바꾸면 기준과 그룹 id를 함께 보낸다", async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderEdit();
    await user.click(screen.getByRole("button", { name: "관심종목 그룹" }));
    await waitFor(() => expect(screen.getByRole("option", { name: "2차전지 · 0종목" })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText("관심종목 그룹"), "9");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(onUpdate).toHaveBeenCalledWith({ targetType: "GROUP", targetGroupId: 9 });
  });

  it("같은 유형 안에서 종목을 바꾸면 stockId만 보낸다", async () => {
    const user = userEvent.setup();
    const { onUpdate } = renderEdit();
    await user.click(screen.getByRole("button", { name: "카카오 선택 해제" }));
    await user.type(screen.getByPlaceholderText("종목명 또는 코드 검색"), "삼성");
    await user.click(await screen.findByRole("button", { name: /삼성전자/ }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(onUpdate).toHaveBeenCalledWith({ stockId: 42 });
  });

  describe("그룹이 지워져 꺼진 규칙", () => {
    const orphan: WatchRuleResponse = {
      ...rule, stockId: null, targetType: "GROUP", targetGroupId: 3, targetGroupName: null, targetGroupMissing: true, isActive: false,
    };

    it("새 대상을 고르라고 안내하고, 대상을 바꾸지 않으면 이름 같은 값만 고친다", async () => {
      const user = userEvent.setup();
      const { onUpdate } = renderEdit(orphan);
      expect(screen.getByText(/대상 관심종목 그룹이 삭제되어/)).toBeInTheDocument();
      await waitFor(() => expect(screen.getByRole("option", { name: "삭제된 그룹 — 새 그룹을 고르세요" })).toBeInTheDocument());
      expect(screen.queryByRole("checkbox", { name: "저장하면서 규칙 다시 켜기" })).not.toBeInTheDocument();
      await user.clear(screen.getByLabelText("규칙 이름"));
      await user.type(screen.getByLabelText("규칙 이름"), "옛 그룹");
      await user.click(screen.getByRole("button", { name: "변경 저장" }));
      expect(onUpdate).toHaveBeenCalledWith({ name: "옛 그룹" });
    });

    it("새 그룹을 고르면 다시 켜기를 함께 보낸다(끌 수도 있다)", async () => {
      const user = userEvent.setup();
      const { onUpdate } = renderEdit(orphan);
      await waitFor(() => expect(screen.getByRole("option", { name: "반도체 · 2종목" })).toBeInTheDocument());
      await user.selectOptions(screen.getByLabelText("관심종목 그룹"), "4");
      const reactivate = screen.getByRole("checkbox", { name: "저장하면서 규칙 다시 켜기" });
      expect(reactivate).toHaveAttribute("aria-checked", "true");
      await user.click(screen.getByRole("button", { name: "변경 저장" }));
      expect(onUpdate).toHaveBeenLastCalledWith({ targetGroupId: 4, isActive: true });

      await user.click(reactivate);
      await user.click(screen.getByRole("button", { name: "변경 저장" }));
      expect(onUpdate).toHaveBeenLastCalledWith({ targetGroupId: 4 });
    });

    it("종목 하나로 바꿔도 다시 켤 수 있다", async () => {
      const user = userEvent.setup();
      const { onUpdate } = renderEdit(orphan);
      await user.click(screen.getByRole("button", { name: "종목 하나" }));
      await user.type(screen.getByPlaceholderText("종목명 또는 코드 검색"), "삼성");
      await user.click(await screen.findByRole("button", { name: /삼성전자/ }));
      await user.click(screen.getByRole("button", { name: "변경 저장" }));
      expect(onUpdate).toHaveBeenCalledWith({ targetType: "STOCK", stockId: 42, isActive: true });
    });
  });

  it("서버 오류 메시지를 폼 안에 보인다", () => {
    renderEdit(rule, { serverError: "관심종목 그룹을 찾을 수 없습니다: 9" });
    expect(screen.getByRole("alert")).toHaveTextContent("관심종목 그룹을 찾을 수 없습니다: 9");
  });

  it("Esc·취소 버튼으로 닫는다, 저장 중에는 버튼이 잠긴다", async () => {
    const user = userEvent.setup();
    const { onCancel, unmount } = renderEdit();
    await user.click(screen.getByLabelText("규칙 이름"));
    await user.keyboard("{Escape}");
    expect(onCancel).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "취소" }));
    expect(onCancel).toHaveBeenCalledTimes(2);
    unmount();

    renderEdit(rule, { submitting: true });
    expect(screen.getByRole("button", { name: "저장 중..." })).toBeDisabled();
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();
  });
});
