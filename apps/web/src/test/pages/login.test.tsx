import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// next/navigation mock
const mockPush = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: mockPush }) }));
vi.mock("next/link", () => ({ default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a> }));

const mockLogin     = vi.fn();
const mockSaveTokens = vi.fn();
vi.mock("@/services/auth", () => ({ login: mockLogin, saveTokens: mockSaveTokens }));

// 동적 import 방지
let LoginPage: React.ComponentType;

beforeEach(async () => {
  mockPush.mockReset();
  mockLogin.mockReset();
  mockSaveTokens.mockReset();
  ({ default: LoginPage } = await import("@/app/login/page"));
});

describe("LoginPage", () => {
  it("이메일·비밀번호 입력 후 로그인 성공하면 홈으로 이동", async () => {
    mockLogin.mockResolvedValueOnce({ accessToken: "access", refreshToken: "refresh" });

    render(<LoginPage />);

    await userEvent.type(screen.getByPlaceholderText("이메일"),   "user@example.com");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "password123");
    fireEvent.submit(screen.getByRole("button", { name: /로그인/ }));

    await waitFor(() => {
      expect(mockLogin).toHaveBeenCalledWith("user@example.com", "password123");
      expect(mockPush).toHaveBeenCalledWith("/");
    });
  });

  it("로그인 실패 시 에러 메시지를 표시한다", async () => {
    mockLogin.mockRejectedValueOnce(new Error("이메일 또는 비밀번호가 올바르지 않습니다."));

    render(<LoginPage />);
    await userEvent.type(screen.getByPlaceholderText("이메일"),   "bad@test.com");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "wrongpwd");
    fireEvent.submit(screen.getByRole("button", { name: /로그인/ }));

    await waitFor(() => expect(screen.getByText(/이메일 또는 비밀번호/)).toBeInTheDocument());
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("회원가입 링크가 /signup을 가리킨다", async () => {
    render(<LoginPage />);
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute("href", "/signup");
  });

  it("이메일·비밀번호를 비워두고 제출하면 검증 오류를 표시하고 로그인을 호출하지 않는다", async () => {
    render(<LoginPage />);

    fireEvent.submit(screen.getByRole("button", { name: /로그인/ }));

    expect(await screen.findByText("이메일을 입력해주세요.")).toBeInTheDocument();
    expect(screen.getByText("비밀번호를 입력해주세요.")).toBeInTheDocument();
    expect(mockLogin).not.toHaveBeenCalled();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("이메일만 입력하고 제출하면 비밀번호 오류만 표시한다", async () => {
    render(<LoginPage />);

    await userEvent.type(screen.getByPlaceholderText("이메일"), "user@example.com");
    fireEvent.submit(screen.getByRole("button", { name: /로그인/ }));

    expect(await screen.findByText("비밀번호를 입력해주세요.")).toBeInTheDocument();
    expect(screen.queryByText("이메일을 입력해주세요.")).not.toBeInTheDocument();
    expect(mockLogin).not.toHaveBeenCalled();
  });
});
