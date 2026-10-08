import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({
  token: "old" as string | null,
  refreshTokens: vi.fn(),
  saveTokens: vi.fn(),
  clearTokens: vi.fn(),
}));

vi.mock("./auth", () => ({
  getAccessToken: () => auth.token,
  refreshTokens: auth.refreshTokens,
  saveTokens: (t: { accessToken: string }) => {
    auth.token = t.accessToken;
    auth.saveTokens(t);
  },
  clearTokens: () => {
    auth.token = null;
    auth.clearTokens();
  },
}));

import { authFetch } from "./api";

function bearer(init?: RequestInit): string | undefined {
  return (init?.headers as Record<string, string> | undefined)?.Authorization;
}

describe("authFetch", () => {
  const fetchMock = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>();

  beforeEach(() => {
    auth.token = "old";
    auth.refreshTokens.mockReset();
    auth.saveTokens.mockReset();
    auth.clearTokens.mockReset();
    fetchMock.mockReset();
    // 새 토큰이면 200, 아니면 401 — 만료 직후 서버처럼 동작
    fetchMock.mockImplementation(async (_input, init) =>
      new Response("{}", { status: bearer(init) === "Bearer new" ? 200 : 401 }),
    );
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("refreshes once for concurrent 401s and retries every request", async () => {
    let release!: () => void;
    auth.refreshTokens.mockImplementation(
      () => new Promise((resolve) => { release = () => resolve({ accessToken: "new" }); }),
    );

    const pending = Promise.all([authFetch("/api/a"), authFetch("/api/b"), authFetch("/api/c")]);
    await vi.waitFor(() => expect(auth.refreshTokens).toHaveBeenCalled());
    release();
    const responses = await pending;

    expect(responses.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(auth.refreshTokens).toHaveBeenCalledTimes(1);
    expect(auth.saveTokens).toHaveBeenCalledTimes(1);
  });

  it("returns the original 401 and clears tokens when the refresh fails", async () => {
    auth.refreshTokens.mockRejectedValue(new Error("expired"));

    const responses = await Promise.all([authFetch("/api/a"), authFetch("/api/b")]);

    expect(responses.map((r) => r.status)).toEqual([401, 401]);
    expect(auth.refreshTokens).toHaveBeenCalledTimes(1);
    expect(auth.clearTokens).toHaveBeenCalledTimes(1);
  });

  it("retries with a token another request already refreshed, without refreshing again", async () => {
    fetchMock.mockImplementationOnce(async () => {
      auth.token = "new"; // 이 요청이 나가 있는 동안 다른 요청이 재발급을 끝냈다
      return new Response("{}", { status: 401 });
    });

    const res = await authFetch("/api/a");

    expect(res.status).toBe(200);
    expect(auth.refreshTokens).not.toHaveBeenCalled();
    expect(bearer(fetchMock.mock.calls[1][1])).toBe("Bearer new");
  });

  it("passes non-401 responses through untouched", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 500 }));

    const res = await authFetch("/api/a");

    expect(res.status).toBe(500);
    expect(auth.refreshTokens).not.toHaveBeenCalled();
  });
});
