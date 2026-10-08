import { describe, it, expect, vi, beforeEach } from "vitest";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));

import { PAPER_INITIAL_CAPITALS, fetchPaperAccount, openPaperAccount, savePreferences } from "@/services/onboarding";
import { loginErrorMessage } from "@/lib/loginError";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => authFetch.mockReset());

describe("onboarding services", () => {
  it("시작 자금은 서버 화이트리스트와 같은 세 값", () => {
    expect([...PAPER_INITIAL_CAPITALS]).toEqual([10_000_000, 30_000_000, 100_000_000]);
  });

  it("관심 분야는 서버 enum 값으로 PUT 한다(userId를 보내지 않는다)", async () => {
    authFetch.mockResolvedValueOnce(json({ interestSectors: ["BIO"], usageStyle: "QUANT", updatedAt: "2026-10-08T00:00:00Z" }));
    await savePreferences(["BIO"], "QUANT");
    const [url, init] = authFetch.mock.calls[0];
    expect(url).toBe("/api/users/me/preferences");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body)).toEqual({ interestSectors: ["BIO"], usageStyle: "QUANT" });
  });

  it("409(이미 다른 시작 자금의 계좌)는 서버 메시지를 그대로 던진다", async () => {
    authFetch.mockResolvedValueOnce(json({ status: 409, message: "이미 시작 자금 10,000,000원으로 만든 모의 계좌가 있습니다." }, 409));
    await expect(openPaperAccount(100_000_000)).rejects.toThrow("이미 시작 자금");
  });

  it("계좌가 없으면(204) null", async () => {
    authFetch.mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect(await fetchPaperAccount()).toBeNull();
  });
});

describe("loginErrorMessage", () => {
  it("아는 코드는 고정 문구, 모르는 코드는 일반 문구, 없으면 null", () => {
    expect(loginErrorMessage("oauth2")).toContain("소셜 로그인에 실패했습니다");
    expect(loginErrorMessage("<img src=x onerror=alert(1)>")).toBe("로그인 중 문제가 발생했습니다. 다시 시도해주세요.");
    expect(loginErrorMessage("__proto__")).toBe("로그인 중 문제가 발생했습니다. 다시 시도해주세요.");
    expect(loginErrorMessage(null)).toBeNull();
    expect(loginErrorMessage("")).toBeNull();
  });
});
