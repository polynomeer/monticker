import { authFetch } from "./api";

/**
 * 일회성 구독 결제 (ADR-059).
 *
 * 흐름이 세 단계인 이유는 **금액과 orderId를 서버가 쥐어야** 하기 때문이다:
 *
 *   1. prepare  — 서버가 orderId와 결제 금액을 정해 PENDING 기록으로 남긴다
 *   2. 토스 SDK — 그 값으로 결제창을 띄운다 (성공/실패 모두 콜백 페이지로 리다이렉트)
 *   3. confirm  — paymentKey와 orderId만 보낸다. 금액은 보내지 않는다
 *
 * 예전 confirm은 금액을 요청 바디로 받았다. 토스 confirm은 "보낸 금액이 실제 결제 금액과
 * 같은가"만 검증하므로, 100원을 결제하고 `{amount:100, planCode:"PRO"}`로 confirm하면
 * 9,900원 플랜이 활성화됐다. 그래서 프론트는 금액을 **다룰 수조차 없게** 만들었다 —
 * prepare가 돌려준 값을 SDK에 그대로 넘기고, confirm에는 아예 싣지 않는다.
 *
 * 정기결제(빌링키) 흐름은 별개다 — services/billing.ts 참고.
 */

export interface PreparedPayment {
  orderId: string;
  amount: number;
  planCode: string;
}

export interface ConfirmedPayment {
  success: boolean;
  pgTransactionId: string | null;
  message: string | null;
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  // 서버는 실패 사유를 ConfirmResponse.message 로 내려준다. JSON이 아니면 fallback.
  try {
    const body = await res.json();
    return body?.message ?? fallback;
  } catch {
    return fallback;
  }
}

export async function preparePayment(planCode: string): Promise<PreparedPayment> {
  const res = await authFetch("/api/subscription/payment/prepare", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ planCode }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, "결제를 준비하지 못했습니다."));
  return res.json();
}

export async function confirmPayment(paymentKey: string, orderId: string): Promise<ConfirmedPayment> {
  const res = await authFetch("/api/subscription/payment/confirm", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      // orderId는 서버가 결제 1건당 하나씩 만든 값이라 멱등 키로 그대로 알맞다.
      // 콜백 페이지가 새로고침되거나 두 번 마운트돼도 승인이 두 번 나가지 않는다.
      "X-Idempotency-Key": orderId,
    },
    // amount도 planCode도 보내지 않는다 — 서버가 orderId로 기록을 찾아 자기가 정한 금액을 쓴다.
    body: JSON.stringify({ paymentKey, orderId }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, "결제 승인에 실패했습니다."));
  return res.json();
}

/**
 * 토스 결제창을 띄운다. 성공하면 이 함수는 **반환되지 않는다** — 브라우저가 토스로,
 * 이어서 successUrl로 리다이렉트된다(billing의 requestBillingAuth와 같은 방식).
 */
export async function openTossPaymentWindow(prepared: PreparedPayment, planName: string): Promise<void> {
  const clientKey = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;
  if (!clientKey) throw new Error("결제 설정이 올바르지 않습니다. 잠시 후 다시 시도해주세요.");

  const { loadTossPayments, ANONYMOUS } = await import("@tosspayments/tosspayments-sdk");
  const tossPayments = await loadTossPayments(clientKey);
  // 일회성 결제는 저장된 수단을 쓰지 않으므로 customerKey가 필요 없다.
  const payment = tossPayments.payment({ customerKey: ANONYMOUS });

  await payment.requestPayment({
    method: "CARD",
    amount: { currency: "KRW", value: prepared.amount },
    orderId: prepared.orderId,
    orderName: `${planName} 플랜 구독`,
    // 성공·실패 모두 같은 콜백으로 보낸다. 성공 시 paymentKey/orderId/amount가,
    // 실패 시 code/message가 쿼리 파라미터로 붙는다(콜백 페이지가 구분한다).
    successUrl: `${window.location.origin}/subscription/payment/callback`,
    failUrl: `${window.location.origin}/subscription/payment/callback`,
    card: { flowMode: "DEFAULT", useEscrow: false, useCardPoint: false, useAppCardOnly: false },
  });
}

/** 토스 클라이언트 키가 없으면 실결제 모드가 아니다 — Mock PG의 /subscribe 경로를 쓴다. */
export function isRealPaymentEnabled(): boolean {
  return !!process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;
}
