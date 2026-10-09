import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

let params = new URLSearchParams();
vi.mock("next/navigation", () => ({ useSearchParams: () => params }));
vi.mock("next/link", () => ({ default: ({ href, children, className }: { href: string; children: React.ReactNode; className?: string }) => <a href={href} className={className}>{children}</a> }));

const fetchMock = vi.fn();
let Page: React.ComponentType;

beforeEach(async () => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
  ({ default: Page } = await import("@/app/unsubscribe/page"));
});

afterEach(() => vi.unstubAllGlobals());

const TOKEN = "v1.weekly_report.42.1700000000.abcDEF_-0123456789abcdefghijklmnopqrstuvwx";

/** ADR-102 — 로그인 없는 수신 거부 확인 화면 */
describe("수신 거부 화면", () => {
  it("열기만 해서는 서버를 부르지 않는다 — 메일 스캐너의 미리 열기 대비", async () => {
    params = new URLSearchParams({ token: TOKEN });
    render(<Page />);
    expect(screen.getByRole("button", { name: "수신 거부" })).toBeInTheDocument();
    expect(screen.getByText("알림 설정에서 관리하기").closest("a")).toHaveAttribute("href", "/settings/notifications");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("버튼을 누르면 RFC 8058과 같은 POST를 로그인 정보 없이 보내고 완료를 보여준다", async () => {
    params = new URLSearchParams({ token: TOKEN });
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ status: "unsubscribed" }), { status: 200 }));
    render(<Page />);

    await userEvent.click(screen.getByRole("button", { name: "수신 거부" }));

    await waitFor(() => expect(screen.getByText("수신 거부되었습니다")).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(`/api/unsubscribe?token=${encodeURIComponent(TOKEN)}`);
    expect(init.method).toBe("POST");
    expect(init.body).toBe("List-Unsubscribe=One-Click");
    expect(init.credentials).toBe("omit");
    expect(init.headers).not.toHaveProperty("Authorization");
  });

  it("서버가 400이면 잘못된 링크로 안내하고 설정 화면 링크를 준다", async () => {
    params = new URLSearchParams({ token: "tampered" });
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 400 }));
    render(<Page />);

    await userEvent.click(screen.getByRole("button", { name: "수신 거부" }));

    await waitFor(() => expect(screen.getByText("유효하지 않은 링크입니다")).toBeInTheDocument());
    expect(screen.getByText("알림 설정에서 끄기").closest("a")).toHaveAttribute("href", "/settings/notifications");
  });

  it("일시 오류면 버튼을 남겨 다시 시도하게 한다", async () => {
    params = new URLSearchParams({ token: TOKEN });
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 503 }));
    render(<Page />);

    await userEvent.click(screen.getByRole("button", { name: "수신 거부" }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("잠시 후 다시 시도"));
    expect(screen.getByRole("button", { name: "수신 거부" })).toBeEnabled();
  });

  it("토큰 없이 들어오면 서버를 부르지 않고 잘못된 링크로 안내한다", () => {
    params = new URLSearchParams();
    render(<Page />);
    expect(screen.getByText("유효하지 않은 링크입니다")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "수신 거부" })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
