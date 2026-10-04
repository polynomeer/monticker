import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

const mockPush = vi.fn();
let params = new URLSearchParams();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => params,
}));

const mockConfirm = vi.fn();
vi.mock("@/services/payment", () => ({ confirmPayment: mockConfirm }));

let Page: React.ComponentType;

beforeEach(async () => {
  mockPush.mockReset();
  mockConfirm.mockReset();
  ({ default: Page } = await import("@/app/subscription/payment/callback/page"));
});

/**
 * ADR-059 — 토스 리다이렉트 착지점. 돈이 이미 빠진 뒤의 화면이라 오동작의 대가가 크다.
 */
describe("결제 콜백", () => {
  it("성공 리다이렉트면 paymentKey·orderId로 승인을 요청한다", async () => {
    params = new URLSearchParams({ paymentKey: "pk_1", orderId: "sub_42_abc", amount: "9900" });
    mockConfirm.mockResolvedValueOnce({ success: true, pgTransactionId: "tx", message: null });

    render(<Page />);

    await waitFor(() => expect(screen.getByText("결제 완료")).toBeInTheDocument());
    expect(mockConfirm).toHaveBeenCalledWith("pk_1", "sub_42_abc");
  });

  // 이게 이 화면의 핵심 규칙이다. 주소창의 amount 를 승인 금액으로 쓰면 그게 곧 결제 우회다.
  it("주소창의 amount는 쓰지 않는다 — 금액은 서버가 쥔다", async () => {
    params = new URLSearchParams({ paymentKey: "pk_1", orderId: "sub_42_abc", amount: "100" });
    mockConfirm.mockResolvedValueOnce({ success: true, pgTransactionId: "tx", message: null });

    render(<Page />);

    await waitFor(() => expect(mockConfirm).toHaveBeenCalled());
    // 인자가 둘뿐이다 — 금액이 끼어들 자리가 없다.
    expect(mockConfirm.mock.calls[0]).toHaveLength(2);
    expect(mockConfirm.mock.calls[0]).not.toContain("100");
  });

  it("실패 리다이렉트면 PG를 찌르지 않고 사유를 보여준다", async () => {
    params = new URLSearchParams({ code: "PAY_PROCESS_CANCELED", message: "사용자가 결제를 취소했습니다." });

    render(<Page />);

    await waitFor(() => expect(screen.getByText("결제 실패")).toBeInTheDocument());
    expect(screen.getByText("사용자가 결제를 취소했습니다.")).toBeInTheDocument();
    expect(mockConfirm).not.toHaveBeenCalled();
  });

  it("파라미터 없이 직접 들어오면 잘못된 접근으로 처리한다", async () => {
    params = new URLSearchParams();

    render(<Page />);

    await waitFor(() => expect(screen.getByText("잘못된 접근입니다.")).toBeInTheDocument());
    expect(mockConfirm).not.toHaveBeenCalled();
  });

  it("승인이 실패하면 자동 확인 안내를 함께 보여준다", async () => {
    // 승인 단계 실패는 "돈은 빠졌는데 구독은 없는" 상태일 수 있다. 사용자가 그걸 알 수 없으니
    // 서버가 되물어 정리한다는 사실을 알려준다(ADR-059 paymentReconciliationJob).
    params = new URLSearchParams({ paymentKey: "pk_1", orderId: "sub_42_abc" });
    mockConfirm.mockRejectedValueOnce(new Error("결제 승인에 실패했습니다."));

    render(<Page />);

    await waitFor(() => expect(screen.getByText("결제 실패")).toBeInTheDocument());
    expect(screen.getByText(/자동으로 확인해 구독에 반영됩니다/)).toBeInTheDocument();
  });

  it("승인은 한 번만 요청한다", async () => {
    params = new URLSearchParams({ paymentKey: "pk_1", orderId: "sub_42_abc" });
    mockConfirm.mockResolvedValueOnce({ success: true, pgTransactionId: "tx", message: null });

    const { rerender } = render(<Page />);
    rerender(<Page />);

    await waitFor(() => expect(screen.getByText("결제 완료")).toBeInTheDocument());
    expect(mockConfirm).toHaveBeenCalledTimes(1);
  });
});
