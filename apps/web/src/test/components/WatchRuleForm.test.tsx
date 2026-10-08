import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { WatchRuleForm } from "@/components/watchrule/WatchRuleForm";

const mockFetch = vi.fn();
global.fetch = mockFetch;

function mockOk(data: unknown) {
  return { ok: true, json: () => Promise.resolve(data) } as Response;
}

const searchHit = [{ id: 19, symbol: "035720", name: "카카오" }];

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockResolvedValue(mockOk(searchHit));
});

/** 종목을 고르는 것까지가 모든 제출의 전제다. */
async function pickStock(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText("종목명 또는 코드 검색"), "카카오");
  await waitFor(() => expect(screen.getByRole("button", { name: /카카오/ })).toBeInTheDocument());
  await user.click(screen.getByRole("button", { name: /카카오/ }));
}

describe("WatchRuleForm", () => {
  it("종목을 고르지 않으면 제출되지 않고 이유를 알려준다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("종목을 선택해주세요");
  });

  it("고른 값을 그대로 담아 제출한다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.selectOptions(screen.getByLabelText("감지할 이벤트"), "PRICE_SPIKE");
    await user.click(screen.getByRole("button", { name: "매도" }));
    await user.clear(screen.getByLabelText("수량 (주)"));
    await user.type(screen.getByLabelText("수량 (주)"), "7");
    await user.clear(screen.getByLabelText("중요도 하한"));
    await user.type(screen.getByLabelText("중요도 하한"), "80");
    await user.selectOptions(screen.getByLabelText("쿨다운"), "3600");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).toHaveBeenCalledWith({
      stockId: 19,
      eventType: "PRICE_SPIKE",
      side: "SELL",
      quantity: 7,
      minImportanceScore: 80,
      cooldownSec: 3600,
    });
  });

  // 서버도 quantity > 0 을 강제하지만(V49 CHECK), 화면에서 먼저 막아야 왕복이 줄고 이유가 분명해진다.
  it("수량이 0이면 제출하지 않는다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.clear(screen.getByLabelText("수량 (주)"));
    await user.type(screen.getByLabelText("수량 (주)"), "0");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("1 이상");
  });

  it("중요도 하한이 100을 넘으면 제출하지 않는다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.clear(screen.getByLabelText("중요도 하한"));
    await user.type(screen.getByLabelText("중요도 하한"), "150");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("0~100");
  });

  it("제출 중에는 버튼이 잠긴다", () => {
    render(<WatchRuleForm onSubmit={vi.fn()} submitting />);
    expect(screen.getByRole("button", { name: "저장 중..." })).toBeDisabled();
  });

  // ADR-077 — 이름·복합 조건·하루 한도는 값이 있을 때만 담긴다
  it("이름·복합 조건·하루 최대 발동을 함께 제출한다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.type(screen.getByLabelText("규칙 이름"), "거래량+급등");
    await user.click(screen.getByRole("button", { name: "+ 가격 급등" }));
    await user.selectOptions(screen.getByLabelText("복합 조건 시간 창"), "3600");
    await user.type(screen.getByLabelText("하루 최대 발동"), "3");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      eventType: "VOLUME_SURGE",
      name: "거래량+급등",
      requiredEventTypes: ["PRICE_SPIKE"],
      conditionWindowSec: 3600,
      dailyLimit: 3,
    }));
  });

  it("하루 최대 발동이 범위를 벗어나면 제출하지 않는다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.type(screen.getByLabelText("하루 최대 발동"), "0");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("1~1000");
  });

  // ADR-095 — 관심종목 그룹 대상·지정가·계좌 %
  it("관심종목 그룹을 대상으로 지정가·계좌 % 규칙을 제출한다", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(mockOk(String(url).startsWith("/api/watchlists")
        ? [{ id: 4, name: "반도체", items: [{}, {}] }, { id: 9, name: "2차전지", items: [] }]
        : searchHit)),
    );
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await user.click(screen.getByRole("button", { name: "관심종목 그룹" }));
    await waitFor(() => expect(screen.getByRole("option", { name: "2차전지 · 0종목" })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText("관심종목 그룹"), "9");
    await user.selectOptions(screen.getByLabelText("주문 유형"), "LIMIT");
    await user.clear(screen.getByLabelText("지정가 오프셋 (bp)"));
    await user.type(screen.getByLabelText("지정가 오프셋 (bp)"), "-120");
    await user.selectOptions(screen.getByLabelText("수량 기준"), "EQUITY_PCT");
    await user.clear(screen.getByLabelText("계좌 비율 (%)"));
    await user.type(screen.getByLabelText("계좌 비율 (%)"), "2.5");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    const value = onSubmit.mock.calls[0][0];
    expect(value).toMatchObject({
      targetType: "GROUP", targetGroupId: 9, orderType: "LIMIT", limitOffsetBps: -120, sizeType: "EQUITY_PCT", equityPct: 2.5,
    });
    expect(value).not.toHaveProperty("stockId");
    expect(value).not.toHaveProperty("quantity");
    expect(screen.queryByText("준비 중")).not.toBeInTheDocument();
  });

  it("지정가 오프셋이 ±1000bp를 넘으면 제출하지 않는다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.selectOptions(screen.getByLabelText("주문 유형"), "LIMIT");
    await user.clear(screen.getByLabelText("지정가 오프셋 (bp)"));
    await user.type(screen.getByLabelText("지정가 오프셋 (bp)"), "1500");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("1000bp");
  });

  it("계좌 비율이 1~25%를 벗어나면 제출하지 않는다", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<WatchRuleForm onSubmit={onSubmit} submitting={false} />);

    await pickStock(user);
    await user.selectOptions(screen.getByLabelText("수량 기준"), "EQUITY_PCT");
    await user.clear(screen.getByLabelText("계좌 비율 (%)"));
    await user.type(screen.getByLabelText("계좌 비율 (%)"), "30");
    await user.click(screen.getByRole("button", { name: "규칙 저장" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("1~25%");
  });
});
