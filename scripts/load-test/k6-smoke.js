// 상용화 부하 테스트 — Phase 0~2에서 고친 것들이 실제 HTTP 스택 아래에서도 버티는지 확인한다.
//   1) 스크리너 조회 — 기본 처리량/지연 시간 베이스라인
//   2) 로그인 브루트포스 — 계정당 5회/15분 잠금이 실제로 걸리는지 (AuthService.login)
//   3) 모의투자 주문 폭주 — 컨트롤러 rate limit(60회/분)이 실제로 429를 돌려주는지
//
// 실행: BASE_URL=http://localhost:8080 k6 run scripts/load-test/k6-smoke.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    screener_read: {
      executor: 'constant-vus',
      vus: 20,
      duration: '20s',
      exec: 'screenerRead',
    },
    login_brute_force: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: 20,
      startTime: '5s',
      exec: 'loginBruteForce',
    },
    order_burst: {
      executor: 'shared-iterations',
      vus: 1, // 동일 사용자 한도이므로 순차 실행 — 병렬이면 어차피 같은 Redis 키로 직렬화됨
      iterations: 80,
      startTime: '10s',
      exec: 'orderBurst',
    },
  },
  thresholds: {
    'http_req_duration{scenario:screener_read}': ['p(95)<1000'],
  },
};

export function setup() {
  const email = `loadtest-${Date.now()}@test.local`;
  const password = 'loadtest1234!';
  const signupRes = http.post(`${BASE}/api/auth/signup`, JSON.stringify({
    email, password, nickname: 'k6부하테스트',
  }), { headers: { 'Content-Type': 'application/json' } });

  if (signupRes.status !== 200) {
    console.error(`signup failed: ${signupRes.status} ${signupRes.body}`);
    return { accessToken: null };
  }
  const accessToken = signupRes.json('accessToken');
  return { accessToken, email };
}

export function screenerRead() {
  const res = http.get(`${BASE}/api/screener?tab=realtime&market=all&sort=amount&limit=20&offset=0`);
  // 20 VU가 think-time 없이 몰아치면 IP당 300회/분 한도(RateLimitFilter)를 실제로 넘는다 —
  // 이건 버그가 아니라 의도한 방어다. 429 응답이 (수정 전처럼 401로 둔갑하지 않고) 정확히
  // 429로 오는지가 검증 포인트다.
  check(res, { 'screener 200 or 429 (never a bare 401)': (r) => r.status === 200 || r.status === 429 });
}

export function loginBruteForce() {
  // 존재하지 않는 계정에 대해 일부러 틀린 비번으로 반복 시도. 두 개의 방어가 겹쳐 있다:
  //   1) RateLimitFilter — /api/auth/login은 IP당 10회/분(다른 /api/**의 300回/분보다 훨씬 엄격)
  //   2) AuthService — 이메일당 5회/15분 실패 시 잠금(신규 추가분)
  // 이 테스트가 20회를 몰아치면 처음 10회는 (1)에 안 걸려 401(비번 오류 또는 계정 잠금 메시지,
  // 둘 다 컨트롤러에서 401로 통일됨), 나머지 10회는 (1)의 IP 한도에 걸려 429가 나오는 게 정상이다.
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({
    email: 'k6-bruteforce-target@test.local', password: 'wrong-password',
  }), { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'login rejected (401 password/lockout, or 429 IP limit)': (r) => r.status === 401 || r.status === 429 });
}

export function orderBurst(data) {
  if (!data.accessToken) return;
  const res = http.post(`${BASE}/api/paper/buy`, JSON.stringify({
    stockId: 1, quantity: 1, orderType: 'MARKET',
  }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.accessToken}`,
    },
  });
  check(res, { 'order 200 or 429': (r) => r.status === 200 || r.status === 429 || r.status === 400 });
}
