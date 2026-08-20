import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AlertPanel from "@/components/stock/AlertPanel";
import { ToastContainer } from "@/components/ui/Toast";
import { useToastStore } from "@/hooks/useToast";

// useToast/useToastStore is app-internal state (a zustand store), not a
// system boundary — mocking it and asserting it "was called" is testing
// implementation, not behavior. Instead we render the real ToastContainer
// alongside AlertPanel and assert on the toast text a user would actually see.
const mockFetch = vi.fn();
global.fetch = mockFetch;

function renderPanel(props: Partial<{ stockId: number; symbol: string }> = {}) {
  return render(
    <>
      <AlertPanel stockId={props.stockId ?? 1} symbol={props.symbol ?? "005930"} />
      <ToastContainer />
    </>
  );
}

beforeEach(() => {
  mockFetch.mockReset();
  useToastStore.setState({ toasts: [] });
});

describe("AlertPanel", () => {
  it("알림 저장 성공 시 성공 토스트를 표시하고 입력값을 비운다", async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({}) } as Response);

    renderPanel();

    const input = screen.getByLabelText("기준 가격");
    await userEvent.type(input, "80000");
    fireEvent.submit(input.closest("form")!);

    expect(await screen.findByText("알림 저장 완료")).toBeInTheDocument();
    expect(screen.getByText("005930 알림이 등록되었습니다.")).toBeInTheDocument();
    expect(input).toHaveValue(null);

    expect(mockFetch).toHaveBeenCalledWith("/api/alerts/rules", expect.objectContaining({
      method: "POST",
      body: expect.stringContaining("80000"),
    }));
  });

  it("threshold가 비어있으면 API를 호출하지 않는다", async () => {
    renderPanel();

    const form = screen.getByRole("button", { name: /저장/i }).closest("form");
    fireEvent.submit(form!);

    await waitFor(() => expect(mockFetch).not.toHaveBeenCalled());
    expect(screen.queryByText("알림 저장 완료")).not.toBeInTheDocument();
  });

  it("API 오류 시 실패 토스트를 표시한다", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 500 } as Response);

    renderPanel();
    await userEvent.type(screen.getByLabelText("기준 가격"), "80000");
    fireEvent.submit(screen.getByLabelText("기준 가격").closest("form")!);

    expect(await screen.findByText("저장 실패")).toBeInTheDocument();
    expect(screen.getByText("알림 저장 중 오류가 발생했습니다.")).toBeInTheDocument();
  });

  it("네트워크 오류 시 네트워크 오류 토스트를 표시한다", async () => {
    mockFetch.mockRejectedValueOnce(new Error("network fail"));

    renderPanel();
    await userEvent.type(screen.getByLabelText("기준 가격"), "80000");
    fireEvent.submit(screen.getByLabelText("기준 가격").closest("form")!);

    expect(await screen.findByText("네트워크 오류")).toBeInTheDocument();
    expect(screen.getByText("서버에 연결할 수 없습니다.")).toBeInTheDocument();
  });

  it("거래량 급증 알림을 선택하면 기준 가격 입력이 사라진다", async () => {
    renderPanel();

    expect(screen.getByLabelText("기준 가격")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByRole("combobox"), "거래량 급증 알림");

    expect(screen.queryByLabelText("기준 가격")).not.toBeInTheDocument();
  });

  it("가격 이하 알림 선택 후 저장하면 PRICE_BELOW로 요청한다", async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({}) } as Response);

    renderPanel();
    await userEvent.selectOptions(screen.getByRole("combobox"), "가격 이하 알림");
    const input = screen.getByLabelText("기준 가격");
    await userEvent.type(input, "60000");
    fireEvent.submit(input.closest("form")!);

    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith(
      "/api/alerts/rules",
      expect.objectContaining({
        body: JSON.stringify({
          stockId: 1,
          ruleType: "PRICE_BELOW",
          condition: { threshold: 60000 },
        }),
      })
    ));
  });
});
