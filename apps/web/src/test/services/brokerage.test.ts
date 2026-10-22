import { describe, it, expect, vi, beforeEach } from "vitest";

const mockAuthFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: mockAuthFetch }));

let svc: typeof import("@/services/brokerage");

beforeEach(async () => {
  mockAuthFetch.mockReset();
  svc = await import("@/services/brokerage");
});

describe("submitBrokerageOrder", () => {
  // ADR-110 — 키가 없으면 서버의 처리 중 잠금·결과 불명 고정이 걸리지 않아 더블 클릭이 실주문 두 건이 된다.
  it("X-Idempotency-Key를 싣는다", async () => {
    mockAuthFetch.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ id: 7 }) } as Response);
    const req = { symbol: "005930", side: "BUY", orderType: "MARKET", quantity: 1 } as const;

    await svc.submitBrokerageOrder(req, "k-1");

    const [url, init] = mockAuthFetch.mock.calls[0];
    expect(url).toBe("/api/brokerage/orders");
    expect(init.headers["X-Idempotency-Key"]).toBe("k-1");
    expect(JSON.parse(init.body)).toEqual(req);
  });

  it("처리 중(409)이면 서버 메시지로 실패한다", async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ok: false, status: 409,
      json: () => Promise.resolve({ message: "같은 주문을 처리 중입니다", detail: "앞선 요청이 아직 끝나지 않았습니다." }),
    } as Response);

    await expect(svc.submitBrokerageOrder({ symbol: "005930", side: "BUY", orderType: "MARKET", quantity: 1 }, "k-1"))
      .rejects.toMatchObject({ status: 409, message: "같은 주문을 처리 중입니다" });
  });
});
