import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AlertPanel from "@/components/stock/AlertPanel";

const mockToast = vi.fn();
vi.mock("@/hooks/useToast", () => ({ useToast: () => ({ toast: mockToast }) }));

const mockFetch = vi.fn();
global.fetch = mockFetch;

beforeEach(() => { mockFetch.mockReset(); mockToast.mockReset(); });

describe("AlertPanel", () => {
  it("알림 저장 성공 시 toast를 표시한다", async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({}) } as Response);

    render(<AlertPanel stockId={1} symbol="005930" />);

    const input = screen.getByPlaceholderText(/기준 가격/i);
    await userEvent.type(input, "80000");
    fireEvent.submit(input.closest("form")!);

    await waitFor(() => expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ type: "success" })
    ));
    expect(mockFetch).toHaveBeenCalledWith("/api/alerts/rules", expect.objectContaining({
      method: "POST",
      body: expect.stringContaining("80000"),
    }));
  });

  it("threshold가 비어있으면 API 호출하지 않는다", async () => {
    render(<AlertPanel stockId={1} symbol="005930" />);

    const form = screen.getByRole("button", { name: /저장/i }).closest("form");
    fireEvent.submit(form!);

    await waitFor(() => expect(mockFetch).not.toHaveBeenCalled());
  });

  it("API 오류 시 error toast를 표시한다", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 500 } as Response);

    render(<AlertPanel stockId={1} symbol="005930" />);
    await userEvent.type(screen.getByPlaceholderText(/기준 가격/i), "80000");
    fireEvent.submit(screen.getByPlaceholderText(/기준 가격/i).closest("form")!);

    await waitFor(() => expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ type: "error" })
    ));
  });

  it("네트워크 오류 시 error toast를 표시한다", async () => {
    mockFetch.mockRejectedValueOnce(new Error("network fail"));

    render(<AlertPanel stockId={1} symbol="005930" />);
    await userEvent.type(screen.getByPlaceholderText(/기준 가격/i), "80000");
    fireEvent.submit(screen.getByPlaceholderText(/기준 가격/i).closest("form")!);

    await waitFor(() => expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ type: "error" })
    ));
  });
});
