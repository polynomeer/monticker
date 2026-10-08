import { beforeEach, describe, expect, it, vi } from "vitest";

describe("예전 버전이 localStorage에 남긴 refresh token", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  it("처음 토큰을 읽을 때 지운다 — 쿠키로 옮긴 뒤에도 남아 있던 장기 토큰", async () => {
    localStorage.setItem("refreshToken", "legacy-long-lived");
    localStorage.setItem("accessToken", "access");
    const { getAccessToken } = await import("@/services/auth");

    expect(getAccessToken()).toBe("access");
    expect(localStorage.getItem("refreshToken")).toBeNull();
  });

  it("다른 키는 건드리지 않는다", async () => {
    localStorage.setItem("refreshToken", "legacy");
    localStorage.setItem("monticker-a11y", "{}");
    const { getAccessToken } = await import("@/services/auth");

    getAccessToken();

    expect(localStorage.getItem("monticker-a11y")).toBe("{}");
  });
});
