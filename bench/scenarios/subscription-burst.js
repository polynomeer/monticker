/**
 * L-08 subscription-burst — 결제·구독 경로의 부하 하 정합성 검증 (ADR-053).
 *
 * 성능이 아니라 정합성이 본체다. order-burst.js 와 같은 자세로 결제 쪽을 본다 —
 * 주문 경로에는 L-05와 verify.py 가 있었지만, 결제·구독·빌링에는 부하 시나리오가 하나도
 * 없었다. 돈이 오가는 두 경로 중 하나만 검증돼 있었던 셈이다.
 *
 * 각 VU = 독립 계정(sub-<RUN>-<vu>@bench.local, X-Bench 로 레이트리밋 우회 — local 프로파일 전용).
 * 한 VU가 반복마다:
 *   1) 빌링키 등록(최초 1회)   — PG 연동 경로. @RateLimited(10/3600s) 밑으로 맞춘다.
 *   2) 구독/해지 왕복          — payment_records 와 ledger_events 를 만든다.
 *   3) 결제 이력 조회          — 읽기 경로가 쓰기 부하에 눌리는지 본다.
 *
 * 부하 직후 확인할 불변식(verify.py 가 자동 대조한다):
 *   - pg_order_id 중복 0            — 같은 청구주기를 두 번 청구하지 않았다
 *   - 구독 결제 원장 == 결제 기록    — 돈이 기록과 어긋나지 않았다
 *   - 활성 구독 사용자당 1건        — user_subscriptions 유니크가 경합에서 버텼다
 *
 * 실행:
 *   k6 run --env RUN=$(date +%s) --env VUS=50 --env DURATION=60s \
 *          --env BASE_URL=http://localhost:58080 bench/scenarios/subscription-burst.js
 *   python3 bench/consistency/verify.py "sub-<RUN>-%@bench.local"
 *
 *   # 운영 PG 모드(PG_MOCK_ENABLED=false, 스텁 PG)에서는 결제 경로가 다르다:
 *   k6 run --env PAY_MODE=confirm ... bench/scenarios/subscription-burst.js
 */
import http from "k6/http";
import { sleep } from "k6";
import { Counter, Rate } from "k6/metrics";

const RUN = __ENV.RUN || "0";
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || "60s";
const BASE = __ENV.BASE_URL || "http://localhost:8080";
const PLAN = __ENV.PLAN || "PRO";
// mock PG(기본 로컬 설정)에서는 /subscribe 가 유료 구독을 만든다.
// PG_MOCK_ENABLED=false 에서는 TossPgClient.requestPayment() 가 "웹훅을 쓰라"는 스텁이라 항상
// 실패하므로(SubscriptionService 주석), 실제 결제가 도는 경로인 /payment/confirm 을 타야 한다.
// 이걸 맞추지 않으면 부하의 본체가 422 가 되어 아무것도 검증하지 못한다.
const PAY_MODE = __ENV.PAY_MODE || "subscribe";   // subscribe | confirm
const SLEEP = Number(__ENV.SLEEP || 3);

const billingOk = new Counter("billing_registered");
const subscribed = new Counter("subscriptions_created");
const cancelled = new Counter("subscriptions_cancelled");
const rejected = new Counter("payment_rejected");     // 4xx — 비즈니스 거절, 정합성 위반 아님
const errors = new Counter("payment_error");          // 5xx
const okRate = new Rate("payment_ok");

export const options = {
  scenarios: { burst: { executor: "ramping-vus", startVUs: 0,
    stages: [{ duration: "15s", target: VUS }, { duration: DURATION, target: VUS }, { duration: "5s", target: 0 }] } },
  thresholds: { payment_ok: ["rate>0.0"] },   // 정합성이 본체라 성능 임계는 느슨하게
};

const tokens = {};
const billed = {};

function token() {
  if (tokens[__VU] !== undefined) return tokens[__VU];
  const email = `sub-${RUN}-${__VU}@bench.local`;
  const r = http.post(`${BASE}/api/auth/signup`,
    JSON.stringify({ email, password: "password123", nickname: `s${__VU}` }),
    { headers: { "Content-Type": "application/json", "X-Bench": "true" } });
  try { tokens[__VU] = JSON.parse(r.body).accessToken; } catch (e) { tokens[__VU] = null; }
  return tokens[__VU];
}

function headers(t, extra) {
  return Object.assign({ "Content-Type": "application/json", Authorization: `Bearer ${t}`, "X-Bench": "true" }, extra || {});
}

function count(r) {
  okRate.add(r.status >= 200 && r.status < 300);
  if (r.status >= 500) errors.add(1);
  else if (r.status >= 400) rejected.add(1);
  return r.status >= 200 && r.status < 300;
}

export default function () {
  const t = token();
  if (!t) { errors.add(1); return; }

  // 1) 빌링키 등록 — VU당 1회. @RateLimited(10/3600s) 를 넘기면 429가 부하의 본체가 되어버린다.
  if (!billed[__VU]) {
    const ck = http.get(`${BASE}/api/subscription/billing/customer-key`, { headers: headers(t), tags: { name: "customer-key" } });
    let customerKey = `bench_${RUN}_${__VU}`;
    try { customerKey = JSON.parse(ck.body).customerKey || customerKey; } catch (e) { /* 기본값 사용 */ }

    const reg = http.post(`${BASE}/api/subscription/billing/register`,
      JSON.stringify({ authKey: `bench_auth_${RUN}_${__VU}`, customerKey }),
      { headers: headers(t), tags: { name: "billing-register" } });
    if (count(reg)) billingOk.add(1);
    billed[__VU] = true;
  }

  // 2) 구독 → 해지 왕복. 결제 기록과 원장이 같이 늘어나야 한다.
  const sub = PAY_MODE === "confirm"
    ? http.post(`${BASE}/api/subscription/payment/confirm`,
        JSON.stringify({
          paymentKey: `bench_pk_${RUN}_${__VU}_${__ITER}`,
          orderId: `bench_order_${RUN}_${__VU}_${__ITER}`,
          amount: 9900, planCode: PLAN,
        }),
        { headers: headers(t, { "X-Idempotency-Key": `sub-${RUN}-${__VU}-${__ITER}` }), tags: { name: "confirm" } })
    : http.post(`${BASE}/api/subscription/subscribe`,
        JSON.stringify({ planCode: PLAN }), { headers: headers(t), tags: { name: "subscribe" } });
  if (count(sub)) subscribed.add(1);

  const cancel = http.post(`${BASE}/api/subscription/cancel`, null, { headers: headers(t), tags: { name: "cancel" } });
  if (count(cancel)) cancelled.add(1);

  // 3) 읽기 경로 — 쓰기 부하 중에도 이력 조회가 살아있는지.
  count(http.get(`${BASE}/api/subscription/payments?page=0&size=20`, { headers: headers(t), tags: { name: "payments" } }));

  sleep(SLEEP);
}
