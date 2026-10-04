import { describe, it, expect, vi, beforeEach } from "vitest";

const mockAuthFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: mockAuthFetch }));

function ok(body: unknown) {
  return { ok: true, json: () => Promise.resolve(body) } as Response;
}
function bad(body: unknown, status = 400) {
  return { ok: false, status, json: () => Promise.resolve(body) } as Response;
}

let svc: typeof import("@/services/payment");

beforeEach(async () => {
  mockAuthFetch.mockReset();
  svc = await import("@/services/payment");
});

describe("preparePayment", () => {
  it("planCode만 보내고 서버가 정한 orderId·금액을 돌려준다", async () => {
    mockAuthFetch.mockResolvedValueOnce(ok({ orderId: "sub_42_abc", amount: 9900, planCode: "PRO" }));

    const prepared = await svc.preparePayment("PRO");

    const [url, init] = mockAuthFetch.mock.calls[0];
    expect(url).toBe("/api/subscription/payment/prepare");
    expect(JSON.parse(init.body)).toEqual({ planCode: "PRO" });
    expect(prepared).toEqual({ orderId: "sub_42_abc", amount: 9900, planCode: "PRO" });
  });

  it("실패하면 서버 메시지를 그대로 올린다", async () => {
    mockAuthFetch.mockResolvedValueOnce(bad({ message: "무료 플랜은 결제가 필요하지 않습니다." }));

    await expect(svc.preparePayment("FREE")).rejects.toThrow("무료 플랜은 결제가 필요하지 않습니다.");
  });
});

describe("confirmPayment", () => {
  // 이 테스트가 이 파일의 본체다. confirm 이 금액을 싣는 순간 결제 우회가 되살아난다 —
  // 토스 confirm 은 "보낸 금액 == 실제 결제 금액"만 검증하므로, 100원을 결제하고
  // amount=100, planCode=PRO 로 confirm 하면 9,900원 플랜이 활성화됐다 (ADR-059).
  it("paymentKey와 orderId만 보낸다 — 금액도 플랜도 싣지 않는다", async () => {
    mockAuthFetch.mockResolvedValueOnce(ok({ success: true, pgTransactionId: "tx", message: null }));

    await svc.confirmPayment("pk_1", "sub_42_abc");

    const [url, init] = mockAuthFetch.mock.calls[0];
    expect(url).toBe("/api/subscription/payment/confirm");
    const body = JSON.parse(init.body);
    expect(body).toEqual({ paymentKey: "pk_1", orderId: "sub_42_abc" });
    expect(body).not.toHaveProperty("amount");
    expect(body).not.toHaveProperty("planCode");
  });

  it("orderId를 멱등 키로 보낸다 — 콜백이 두 번 떠도 승인은 한 번이다", async () => {
    mockAuthFetch.mockResolvedValueOnce(ok({ success: true, pgTransactionId: "tx", message: null }));

    await svc.confirmPayment("pk_1", "sub_42_abc");

    expect(mockAuthFetch.mock.calls[0][1].headers["X-Idempotency-Key"]).toBe("sub_42_abc");
  });

  it("실패하면 서버 메시지를 그대로 올린다", async () => {
    mockAuthFetch.mockResolvedValueOnce(bad({ message: "준비되지 않은 주문입니다. 결제를 다시 시작해주세요." }));

    await expect(svc.confirmPayment("pk_1", "없는-주문")).rejects.toThrow("준비되지 않은 주문입니다");
  });

  it("응답이 JSON이 아니어도 터지지 않고 기본 메시지를 쓴다", async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ok: false, status: 502, json: () => Promise.reject(new Error("not json")),
    } as unknown as Response);

    await expect(svc.confirmPayment("pk_1", "sub_42_abc")).rejects.toThrow("결제 승인에 실패했습니다.");
  });
});

describe("isRealPaymentEnabled", () => {
  it("토스 클라이언트 키가 없으면 실결제 모드가 아니다", () => {
    // 키가 없으면 Mock PG 이고, 그때 /subscribe 를 타야 한다. 운영 PG 모드에서 /subscribe 는
    // TossPgClient.requestPayment 스텁 때문에 항상 실패하므로 분기를 틀리면 결제가 안 된다.
    expect(svc.isRealPaymentEnabled()).toBe(false);
  });
});
