import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import WatchlistAddButton from "@/components/stock/WatchlistAddButton";

// authFetch (services/api.ts) wraps the real fetch boundary with an
// Authorization header read from localStorage — both are real browser/
// network boundaries, so we drive them directly instead of mocking the
// auth module or authFetch itself.
const mockFetch = vi.fn();
global.fetch = mockFetch;

const groups = [
  { id: 1, name: "관심 그룹 1" },
  { id: 2, name: "관심 그룹 2" },
];

function mockOk(data: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(data) } as Response);
}

beforeEach(() => {
  mockFetch.mockReset();
  localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("WatchlistAddButton", () => {
  it("로그인하지 않은 사용자에게는 아무것도 렌더링하지 않는다", () => {
    const { container } = render(<WatchlistAddButton stockId={1} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("로그인했지만 관심그룹이 없으면 아무것도 렌더링하지 않는다", async () => {
    localStorage.setItem("accessToken", "token");
    mockFetch.mockResolvedValueOnce(mockOk([]));

    const { container } = render(<WatchlistAddButton stockId={1} />);

    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith("/api/watchlists", expect.anything()));
    expect(container).toBeEmptyDOMElement();
  });

  it("로그인 상태에서 관심그룹 목록을 불러와 첫 번째 그룹을 기본 선택한다", async () => {
    localStorage.setItem("accessToken", "token");
    mockFetch.mockResolvedValueOnce(mockOk(groups));

    render(<WatchlistAddButton stockId={1} />);

    expect(await screen.findByRole("button", { name: "관심종목 추가" })).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveValue("1");
    expect(screen.getByRole("option", { name: "관심 그룹 1" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "관심 그룹 2" })).toBeInTheDocument();
  });

  it("추가 버튼을 클릭하면 선택된 그룹으로 종목을 추가하고 완료 상태를 보여준다", async () => {
    localStorage.setItem("accessToken", "token");
    mockFetch
      .mockResolvedValueOnce(mockOk(groups))
      .mockResolvedValueOnce({ ok: true, status: 200 } as Response);

    render(<WatchlistAddButton stockId={42} />);
    const button = await screen.findByRole("button", { name: "관심종목 추가" });

    fireEvent.click(button);

    expect(await screen.findByRole("button", { name: /추가됨/ })).toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/watchlists/groups/1/items",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ stockId: 42 }),
      })
    );
  });

  it("이미 추가된 종목(409)이어도 완료 상태로 처리한다", async () => {
    localStorage.setItem("accessToken", "token");
    mockFetch
      .mockResolvedValueOnce(mockOk(groups))
      .mockResolvedValueOnce({ ok: false, status: 409 } as Response);

    render(<WatchlistAddButton stockId={1} />);
    const button = await screen.findByRole("button", { name: "관심종목 추가" });

    fireEvent.click(button);

    expect(await screen.findByRole("button", { name: /추가됨/ })).toBeInTheDocument();
  });

  it("다른 그룹을 선택하면 해당 그룹으로 추가 요청을 보낸다", async () => {
    localStorage.setItem("accessToken", "token");
    mockFetch
      .mockResolvedValueOnce(mockOk(groups))
      .mockResolvedValueOnce({ ok: true, status: 200 } as Response);

    render(<WatchlistAddButton stockId={7} />);
    await screen.findByRole("button", { name: "관심종목 추가" });

    await userEvent.selectOptions(screen.getByRole("combobox"), "관심 그룹 2");
    fireEvent.click(screen.getByRole("button", { name: "관심종목 추가" }));

    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith(
      "/api/watchlists/groups/2/items",
      expect.objectContaining({ body: JSON.stringify({ stockId: 7 }) })
    ));
  });

  it("완료 상태는 2초 후 원래 버튼 문구로 되돌아간다", async () => {
    // shouldAdvanceTime: fake timers still tick forward with real wall-clock
    // time, so Testing Library's internal findBy/waitFor polling (which
    // relies on setTimeout) keeps working; we only need to fast-forward the
    // component's own 2s revert timer explicitly.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    localStorage.setItem("accessToken", "token");
    mockFetch
      .mockResolvedValueOnce(mockOk(groups))
      .mockResolvedValueOnce({ ok: true, status: 200 } as Response);

    render(<WatchlistAddButton stockId={1} />);
    const button = await screen.findByRole("button", { name: "관심종목 추가" });

    fireEvent.click(button);
    expect(await screen.findByRole("button", { name: /추가됨/ })).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByRole("button", { name: "관심종목 추가" })).toBeInTheDocument();
  });
});
