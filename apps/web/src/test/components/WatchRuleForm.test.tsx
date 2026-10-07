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
});
