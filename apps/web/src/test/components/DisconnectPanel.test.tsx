import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const mutate = vi.fn();
const state: Record<string, unknown> = {};

vi.mock("@/hooks/useBrokerage", () => ({
  useDisconnectBrokerage: () => ({ mutate, reset: vi.fn(), isPending: false, isError: false, isSuccess: false, error: null, data: undefined, ...state }),
}));

import DisconnectPanel from "@/components/brokerage/DisconnectPanel";

describe("DisconnectPanel", () => {
  beforeEach(() => {
    mutate.mockReset();
    for (const k of Object.keys(state)) delete state[k];
  });

  it("첫 클릭은 확인만 묻고, 두 번째 클릭에서야 해지를 요청한다", async () => {
    render(<DisconnectPanel />);

    await userEvent.click(screen.getByRole("button", { name: /연동 해지/ }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByText(/되돌릴 수 없습니다/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "키 삭제하고 해지" }));
    expect(mutate).toHaveBeenCalledTimes(1);
  });

  it("확인 단계에서 취소하면 요청하지 않는다", async () => {
    render(<DisconnectPanel />);

    await userEvent.click(screen.getByRole("button", { name: /연동 해지/ }));
    await userEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: /연동 해지/ })).toBeInTheDocument();
  });

  it("서버가 거부하면 그 사유를 보여준다", () => {
    Object.assign(state, { isError: true, error: new Error("체결 여부를 증권사에서 아직 확인 중인 주문이 1건 있어 연동을 해지할 수 없습니다.") });
    render(<DisconnectPanel />);

    expect(screen.getByRole("alert")).toHaveTextContent("확인 중인 주문이 1건");
  });
});
