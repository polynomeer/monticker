import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("@/services/auth", () => ({ getAccessToken: () => "token", logout: vi.fn(), AUTH_CHANGED_EVENT: "auth-changed" }));

import { useAuth } from "@/hooks/useAuth";

const seen: boolean[] = [];
function Probe() {
  const { isLoggedIn } = useAuth();
  seen.push(isLoggedIn);
  return <span>{String(isLoggedIn)}</span>;
}

describe("useAuth 재마운트", () => {
  it("첫 렌더는 서버와 맞춰 false, 이후 마운트는 처음부터 true — enabled가 false → true로 깜빡이지 않는다", () => {
    const first = render(<Probe />);
    expect(seen[0]).toBe(false);
    expect(screen.getByText("true")).toBeInTheDocument();
    first.unmount();

    seen.length = 0;
    render(<Probe />);
    expect(seen.every(Boolean)).toBe(true);
  });
});
