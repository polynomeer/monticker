# monticker — 정산 시스템 설계

> Read this when: 정산 도메인 코드를 작성하거나, 새 정산 플로우를 추가하거나, 외부 연동 인터페이스를 설계할 때.

---

## 개요

monticker의 정산 시스템은 4개의 독립 도메인으로 구성된다.

| # | 도메인 | 목적 | 실거래 여부 | 구현 상태 |
|---|--------|------|------------|-----------|
| ① | **페이퍼트레이딩 정산** | 모의투자 체결 건의 T+2 결제·원장 반영 | 모의 (실머니 없음) | ✅ 동작 중 (배치 평일 16:30) |
| ② | **전략 마켓 수익 분배** | 전략 구독 수익을 제작자 계정에 적립·출금 | 서비스 내 포인트 | 🟡 적립·출금 요청·승인까지. **실제 송금은 코드 밖** |
| ③ | **구독료 정산** | 플랜별 월 이용료 PG 결제 및 청구서 관리 | 실 PG (토스페이먼츠) | 🟡 실연동 코드 완료. **라이브 결제 미검증** (`PG_MOCK_ENABLED` 기본 true) |
| ④ | **실거래 증권사 정산** | 실제 주식 매매를 증권사 API에 위임·정산 수신 | 실 증권사 (KIS·토스증권) | 🟡 실연동 코드 완료. **실계좌 미검증** (`BROKERAGE_MOCK_ENABLED` 기본 true) |

> **"코드 완료 / 미검증"의 뜻**: 외부 API를 실제로 호출하는 구현이 있고 단위·통합 테스트를 통과하지만,
> 실제 키·실계좌로 한 번도 돌려본 적이 없다는 의미다. 남은 것은 코드가 아니라 실명·사업자 인증과
> 법무 검토다 — [human-action-items.md](human-action-items.md), [launch-plan.md](launch-plan.md) 참고.

각 도메인은 `settlement/` 패키지 하위에 독립 모듈로 배치되며, 다른 도메인의 Repository를 직접 호출하지 않고 도메인 이벤트를 통해서만 통신한다.

---

## ① 페이퍼트레이딩 정산

### 개념

실제 주식 시장은 체결(Fill) 후 T+2 영업일에 대금을 결제한다. 페이퍼트레이딩 정산은 이 프로세스를 모의 구현하여 사용자가 정산 사이클을 학습할 수 있게 한다.

```
체결(Fill) 발생
  → paper_settlements 레코드 생성 (status=PENDING, settle_date=T+2)
  → [D+2 영업일 배치] status=PENDING → SETTLED
  → 수수료·세금 차감 후 net_amount 계산
  → LedgerEvent 기록 (SETTLEMENT_COMPLETE)
  → paper_accounts.cash 갱신
```

### 수수료·세금 계산 (한국 기준)

| 항목 | 기준 | 비율 |
|------|------|------|
| 매매 수수료 | 체결 금액 | 0.015% (온라인 위탁) |
| 증권거래세 | 매도 체결 금액 | 0.18% (KOSPI), 0.18% (KOSDAQ) |
| 농어촌특별세 | 매도 체결 금액 | 0.15% → KOSPI만 (거래세에 포함) |

```kotlin
data class SettlementCalculation(
    val grossAmount: BigDecimal,   // 체결 금액 (qty × price)
    val fee: BigDecimal,           // 수수료
    val tax: BigDecimal,           // 세금 (매도 시만)
    val netAmount: BigDecimal,     // grossAmount ∓ fee - tax
    val side: String,              // BUY | SELL
)
```

### DB 스키마

```sql
CREATE TABLE paper_settlements (
    id              BIGSERIAL PRIMARY KEY,
    fill_id         BIGINT         NOT NULL REFERENCES fills(id) UNIQUE,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    stock_id        BIGINT         NOT NULL REFERENCES stocks(id),
    side            VARCHAR(4)     NOT NULL,
    quantity        INTEGER        NOT NULL,
    fill_price      NUMERIC(18,4)  NOT NULL,
    gross_amount    NUMERIC(18,4)  NOT NULL,
    fee             NUMERIC(18,4)  NOT NULL DEFAULT 0,
    tax             NUMERIC(18,4)  NOT NULL DEFAULT 0,
    net_amount      NUMERIC(18,4)  NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
                                   -- PENDING | SETTLED | FAILED
    settle_date     DATE           NOT NULL,  -- T+2 영업일
    settled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_paper_settlements_user_status
    ON paper_settlements (user_id, status, settle_date);
CREATE INDEX idx_paper_settlements_settle_date
    ON paper_settlements (settle_date, status);
```

### API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/settlement/paper` | 내 정산 내역 (페이지네이션) |
| `GET` | `/api/settlement/paper/pending` | 정산 대기 중인 건 조회 |
| `GET` | `/api/settlement/paper/{fillId}` | 특정 체결 건 정산 상세 |

### 배치 처리

Spring Batch Job `PaperSettlementJob`이 매일 장 마감 후(16:00 KST) 실행된다.

```
PaperSettlementJob
  → PaperSettlementReader   (settle_date <= today AND status=PENDING)
  → PaperSettlementProcessor (net_amount 재계산, status=SETTLED)
  → PaperSettlementWriter   (DB update + LedgerEvent publish)
```

---

## ② 전략 마켓 수익 분배

### 개념

사용자가 전략을 마켓에 공유하면, 구독자가 발생할 때마다 구독료의 일부가 전략 제작자에게 적립된다. 적립금은 서비스 내 포인트(크레딧) 형태로 관리되며 출금 요청 시 별도 검토 후 지급된다.

```
구독자 결제 완료 이벤트
  → 수익 배분 계산 (구독료 × 제작자 수익률 70%)
  → creator_earnings 적립
  → 제작자에게 알림

제작자 출금 요청
  → creator_payouts 생성 (status=REQUESTED)
  → 관리자 검토 → APPROVED | REJECTED
  → APPROVED: PAID 로 전환 + AVAILABLE earnings 를 선입선출로 PAID_OUT 처리 + 원장 기록
```

> ⚠️ **실제 송금은 코드에 없다.** `CreatorEarningsService.approvePayout()`은 상태를 `PAID`로 바꾸고
> 원장에 기록할 뿐, 계좌 이체를 수행하지 않는다 — 관리자가 별도로 이체한 뒤 승인을 누르는 것을
> 전제한 설계다. 자동 지급을 붙이려면 지급대행(페이아웃) 연동과 세무 처리(원천징수)가 선행돼야 한다.

### 수익 배분 구조

| 구분 | 비율 | 비고 |
|------|------|------|
| 전략 제작자 | 70% | `creator_earnings` 적립 |
| 플랫폼 수수료 | 30% | monticker 운영 수익 |

### DB 스키마

```sql
CREATE TABLE creator_earnings (
    id              BIGSERIAL PRIMARY KEY,
    creator_id      BIGINT         NOT NULL REFERENCES users(id),
    strategy_id     BIGINT         NOT NULL REFERENCES strategy_market(id),
    subscriber_id   BIGINT         NOT NULL REFERENCES users(id),
    payment_id      BIGINT         NOT NULL REFERENCES payment_records(id),
    gross_amount    NUMERIC(18,4)  NOT NULL,  -- 구독료 전체
    platform_fee    NUMERIC(18,4)  NOT NULL,  -- 플랫폼 수수료 30%
    net_amount      NUMERIC(18,4)  NOT NULL,  -- 제작자 수취 70%
    status          VARCHAR(20)    NOT NULL DEFAULT 'AVAILABLE',
                                   -- AVAILABLE | PAID_OUT | CANCELLED
    earned_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_creator_earnings_creator ON creator_earnings (creator_id, status);

CREATE TABLE creator_payouts (
    id              BIGSERIAL PRIMARY KEY,
    creator_id      BIGINT         NOT NULL REFERENCES users(id),
    amount          NUMERIC(18,4)  NOT NULL,
    bank_name       VARCHAR(50),
    account_number  VARCHAR(50),
    account_holder  VARCHAR(50),
    status          VARCHAR(20)    NOT NULL DEFAULT 'REQUESTED',
                                   -- REQUESTED | APPROVED | REJECTED | PAID
    reject_reason   TEXT,
    requested_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_creator_payouts_creator ON creator_payouts (creator_id, status);
```

### API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/settlement/strategy/earnings` | 내 전략 수익 잔액·이력 |
| `GET` | `/api/settlement/strategy/earnings/summary` | 전략별 수익 요약 |
| `POST` | `/api/settlement/strategy/payout` | 출금 요청 |
| `GET` | `/api/settlement/strategy/payouts` | 출금 요청 이력 |

---

## ③ 구독료 정산

### 개념

monticker는 3단계 구독 플랜을 제공한다. 월 구독료는 토스페이먼츠를 통해 결제되며, 결제 성공 시 구독이 활성화된다.
로컬 개발 환경(`PG_MOCK_ENABLED=true`, 기본값)에서는 `MockPgClient`가 항상 결제 성공을 반환한다.

**토스페이먼츠는 "프론트에서 결제하고 백엔드가 확정"하는 구조라, 서버가 먼저 결제를 요청하는 플로우가 없다.**
그래서 `PgClient.requestPayment()`는 Mock에서만 의미가 있고 `TossPgClient`에서는 실패를 반환한다 —
실 PG 경로에서는 아래 confirm 플로우와 빌링키 자동결제 둘 중 하나를 탄다.

### 구독 플랜

| 플랜 | 월 금액 | 주요 기능 |
|------|---------|----------|
| `FREE` | 0원 | 기본 시세 조회, 관심종목 10개, 알림 3개 |
| `PRO` | 9,900원 | 무제한 알림, AI 요약, 포트폴리오 분석 |
| `QUANT` | 29,900원 | Quant Lab 전체, 전략 마켓 수익 분배, 백테스트 우선 실행 |

### 결제 플로우

**(a) 첫 결제 — 토스 SDK + confirm** (실 PG 경로)

```
프론트: 토스 SDK 결제 위젯 → 사용자 승인 → {paymentKey, orderId, amount}
  → POST /api/subscription/payment/confirm
  → TossPgClient.confirmPayment()  POST /v1/payments/confirm
  → payment_records 저장 (status=SUCCESS | FAILED)
  → SUCCESS: user_subscriptions 갱신, 구독 활성화
```

**(b) 첫 결제 — Mock 경로** (`PG_MOCK_ENABLED=true`)

```
POST /api/subscription/subscribe
  → PgClient.requestPayment()  (MockPgClient: 즉시 SUCCESS)
  → 위와 동일하게 활성화
```
무료 플랜(`FREE`)은 두 경로 모두 PG를 거치지 않고 즉시 적용된다.

**(c) 정기결제 카드 등록 → 월 갱신**

```
프론트: 토스 SDK requestBillingAuth() → successUrl 리다이렉트 {authKey, customerKey}
  → POST /api/subscription/billing/register
  → TossPgClient.issueBillingKey()  POST /v1/billing/authorizations/issue
  → user_billing_keys 저장 (카드사·끝 4자리만 보관)

월 갱신 (Spring Batch, 매월 1일 01:00 KST — BatchJobScheduler.runSubscriptionRenewal)
  → 만료 예정 구독 조회
  → TossPgClient.chargeBilling()  POST /v1/billing/{billingKey}
  → 실패 누적 3회: 구독 FREE 다운그레이드
```

**(d) 웹훅 — 트리거로만 쓰고 값은 믿지 않는다**

```
토스 → POST /api/subscription/payment/webhook
  → 바디의 결제 상태를 신뢰하지 않고 paymentKey 만 꺼낸다
  → TossPgClient.getPaymentStatus()  GET /v1/payments/{paymentKey}  ← 이 값을 신뢰
  → 확인된 상태로 payment_records 갱신
```

> 토스페이먼츠의 일반 결제 상태 웹훅에는 **서명이 없다**(`tosspayments-webhook-signature`는
> `payout.changed`·`seller.changed` 에만 붙는다, 2026-09 개발자센터 확인). 웹훅 바디는 위조할 수 있지만
> 우리 시크릿 키로 인증되는 조회 API는 위조할 수 없다 — 그래서 웹훅은 "다시 물어보라"는 신호로만 쓴다.

### DB 스키마

```sql
CREATE TABLE subscription_plans (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(20)    NOT NULL UNIQUE,  -- FREE | PRO | QUANT
    name         VARCHAR(50)    NOT NULL,
    price        NUMERIC(10,2)  NOT NULL DEFAULT 0,
    currency     VARCHAR(10)    NOT NULL DEFAULT 'KRW',
    features     JSONB          NOT NULL DEFAULT '[]',
    is_active    BOOLEAN        NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE user_subscriptions (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT         NOT NULL REFERENCES users(id) UNIQUE,
    plan_id      BIGINT         NOT NULL REFERENCES subscription_plans(id),
    status       VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
                                -- ACTIVE | EXPIRED | CANCELLED
    started_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE payment_records (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    plan_id         BIGINT         NOT NULL REFERENCES subscription_plans(id),
    pg_provider     VARCHAR(30)    NOT NULL DEFAULT 'MOCK',
                                   -- MOCK | TOSS | IAMPORT
    pg_transaction_id VARCHAR(100),
    amount          NUMERIC(10,2)  NOT NULL,
    currency        VARCHAR(10)    NOT NULL DEFAULT 'KRW',
    status          VARCHAR(20)    NOT NULL,
                                   -- SUCCESS | FAILED | REFUNDED | PENDING
    failure_reason  TEXT,
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_records_user ON payment_records (user_id, created_at DESC);
```

### PG 추상화

```kotlin
interface PgClient {
    fun requestPayment(request: PaymentRequest): PaymentResult          // Mock 전용 경로
    fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult
    fun issueBillingKey(authKey: String, customerKey: String): BillingKeyResult
    fun chargeBilling(billingKey: String, customerKey: String,
                      amount: BigDecimal, orderId: String, orderName: String): PaymentResult
    fun getPaymentStatus(paymentKey: String): PaymentStatusResult       // 웹훅 검증용
}

// 로컬 개발용 Mock — 기본값이다 (PG_MOCK_ENABLED=true)
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "true")
@Primary
class MockPgClient : PgClient { /* 항상 성공 */ }

// 실 PG — PG_MOCK_ENABLED=false 일 때만 뜬다
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "false")
class TossPgClient(
    @Value("\${app.pg.toss.secret-key}") private val secretKey: String,
) : PgClient { /* /v1/payments/confirm, /v1/billing/... 실제 호출 */ }
```

> 프로퍼티 경로는 **`app.pg.toss.secret-key`** 다. 과거 `app.toss.secret-key`로 잘못 참조돼 있어
> `PG_MOCK_ENABLED=false`로 부팅하면 `PlaceholderResolutionException`으로 항상 죽었다(실제 재현). 외부
> 호출에는 결제용 타임아웃(`HttpTimeouts.PAYMENT_READ`)이 걸려 있다 — 카드사 경유라 브로커보다 느리지만
> 무제한은 아니다(resilience-plan P0-2).

### API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/subscription/plans` | 플랜 목록 조회 |
| `GET` | `/api/subscription/me` | 내 구독 현황 |
| `POST` | `/api/subscription/subscribe` | 플랜 구독 — 무료 플랜과 Mock 경로 전용 |
| `POST` | `/api/subscription/cancel` | 구독 해지 |
| `GET` | `/api/subscription/payments` | 결제 이력 |
| `POST` | `/api/subscription/payment/confirm` | 토스 SDK 결제 확정 (실 PG 첫 결제) |
| `POST` | `/api/subscription/payment/webhook` | 토스 웹훅 수신 — 트리거로만 사용 |
| `GET` | `/api/subscription/billing/customer-key` | 정기결제용 customerKey 발급·조회 |
| `POST` | `/api/subscription/billing/register` | authKey → billingKey 발급·저장 |
| `GET` | `/api/subscription/billing` | 등록된 자동결제 카드 상태 |
| `DELETE` | `/api/subscription/billing` | 자동결제 카드 해지 |

---

## ④ 실거래 증권사 정산

### 개념

실제 주식 매매는 증권사 Open API를 통해 위임한다. **한국투자증권(KIS)과 토스증권 두 곳을 지원하며**
([ADR-026](decisions/026-toss-brokerage-integration.md)), 사용자가 연동한 증권사에 따라
`BrokerageClientRegistry`가 구현체를 고른다. 실계좌와 모의계좌를 함께 지원하고, 체결 후 T+2 영업일에
증권사로부터 정산 내역을 수신한다.

**monticker는 자체 브로커 라이선스를 보유하지 않는다** — 실주문은 항상 사용자 본인 명의 계좌의 API 키로
실행되는 BYOK 모델이다([ADR-023](decisions/023-commercialization-pivot.md)). 자격증명은 AES-256-GCM으로
암호화 저장하고, 실주문도 모의투자와 **같은 사전 리스크 게이트**를 통과한다
([ADR-025](decisions/025-real-brokerage-order-safety-gate.md)).

기본값은 `BROKERAGE_MOCK_ENABLED=true`로 `MockBrokerageClient`가 뜬다 — 실계좌 연동 코드는 완성됐지만
실제 앱키로 검증된 적은 없다.

### KIS API 기반 플로우

```
사용자 실계좌 연동 (Access Token 발급)
  → POST /api/brokerage/connect  (appKey, appSecret 입력)
  → KIS OAuth2 토큰 발급 → brokerage_accounts 저장

주문 요청
  → POST /api/brokerage/orders
  → BrokerageClient.submitOrder() → KIS 주문 API 호출
  → brokerage_orders 저장 (status=SUBMITTED)
  → KIS 체결 통보 수신 (웹소켓 또는 폴링)
  → status=FILLED, 체결 단가·수량 갱신

정산 수신 (T+2)
  → 증권사 정산 API 폴링 (Spring Batch, 평일 17:00 KST — BatchJobScheduler.runBrokerageSettlement)
  → brokerage_settlements 저장
  → 원장 이벤트 발행 (BROKERAGE_SETTLEMENT)
```

### Mock 서버 구조

KIS API를 호출하는 `BrokerageClient` 인터페이스를 정의하고, Mock 구현체가 인메모리에서 즉시 체결 결과를 반환한다.

```kotlin
interface BrokerageClient {
    fun issueToken(appKey: String, appSecret: String): BrokerageToken
    fun resolveAccountRef(token: BrokerageToken, accountNumber: String): String? = null
    fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest): BrokerageOrderResult
    fun cancelOrder(credentials: BrokerageCredentials, pgOrderId: String, brokerOrderRef: String?): BrokerageCancelResult
    fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus
    fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem>
    fun getBalance(credentials: BrokerageCredentials): BrokerageBalance
}

// 구현체 3종 — BrokerageClientRegistry 가 사용자의 연동 증권사로 라우팅한다.
MockBrokerageClient   // BROKERAGE_MOCK_ENABLED=true (기본값). 즉시 체결 + T+2 정산 레코드 생성
KisBrokerageClient    // 한국투자증권. 서킷브레이커 "kis"
TossBrokerageClient   // 토스증권 (ADR-026). 같은 패턴의 브레이커
```

> 취소도 실제 브로커에 전달된다([ADR-028](decisions/028-brokerage-order-cancellation.md)).
> 조건부 주문(OCO)은 브로커 네이티브 기능을 쓰지 않고 monticker가 감시하다가 리스크 게이트를 거쳐
> 제출한다 — 네이티브 조건주문은 게이트를 우회하기 때문이다([ADR-032](decisions/032-conditional-orders.md)).

### DB 스키마

```sql
CREATE TABLE brokerage_accounts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    provider        VARCHAR(20)   NOT NULL DEFAULT 'KIS',
                                  -- KIS | MOCK
    account_number  VARCHAR(50)   NOT NULL,
    account_type    VARCHAR(20)   NOT NULL DEFAULT 'REAL',
                                  -- REAL | DEMO
    access_token    TEXT,         -- 암호화 저장
    token_expires_at TIMESTAMPTZ,
    is_active       BOOLEAN       NOT NULL DEFAULT true,
    connected_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider, account_number)
);

CREATE TABLE brokerage_orders (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT         NOT NULL REFERENCES users(id),
    account_id          BIGINT         NOT NULL REFERENCES brokerage_accounts(id),
    stock_id            BIGINT         REFERENCES stocks(id),
    symbol              VARCHAR(20)    NOT NULL,
    side                VARCHAR(4)     NOT NULL,  -- BUY | SELL
    order_type          VARCHAR(10)    NOT NULL,  -- MARKET | LIMIT
    quantity            INTEGER        NOT NULL,
    limit_price         NUMERIC(18,4),
    filled_qty          INTEGER        NOT NULL DEFAULT 0,
    avg_fill_price      NUMERIC(18,4),
    pg_order_id         VARCHAR(100),             -- 증권사 주문 번호
    status              VARCHAR(20)    NOT NULL DEFAULT 'SUBMITTED',
                                       -- SUBMITTED | FILLED | PARTIALLY_FILLED | CANCELLED | REJECTED
    reject_reason       TEXT,
    submitted_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    filled_at           TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_brokerage_orders_user ON brokerage_orders (user_id, submitted_at DESC);

CREATE TABLE brokerage_settlements (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    account_id      BIGINT         NOT NULL REFERENCES brokerage_accounts(id),
    order_id        BIGINT         REFERENCES brokerage_orders(id),
    symbol          VARCHAR(20)    NOT NULL,
    side            VARCHAR(4)     NOT NULL,
    quantity        INTEGER        NOT NULL,
    fill_price      NUMERIC(18,4)  NOT NULL,
    gross_amount    NUMERIC(18,4)  NOT NULL,
    fee             NUMERIC(18,4)  NOT NULL DEFAULT 0,
    tax             NUMERIC(18,4)  NOT NULL DEFAULT 0,
    net_amount      NUMERIC(18,4)  NOT NULL,
    settle_date     DATE           NOT NULL,
    settled_at      TIMESTAMPTZ,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
                                   -- PENDING | SETTLED
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_brokerage_settlements_user ON brokerage_settlements (user_id, settle_date DESC);
```

### API

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/brokerage/connect` | 증권사 계좌 연동 |
| `GET` | `/api/brokerage/account` | 연동 계좌 및 잔고 조회 |
| `POST` | `/api/brokerage/orders` | 실거래 주문 제출 |
| `GET` | `/api/brokerage/orders` | 주문 내역 조회 |
| `DELETE` | `/api/brokerage/orders/{id}` | 주문 취소 |
| `GET` | `/api/brokerage/settlements` | 정산 내역 조회 |
| `GET` | `/api/brokerage/settlements/pending` | 정산 대기 내역 |

---

## 공통 설계 원칙

### 이벤트 기반 연결

각 정산 도메인은 Spring Modulith의 Application Event를 통해 다른 도메인에 사이드이펙트를 유발한다. 직접 Service 호출 금지.

```
OrderFilledEvent
  → PaperSettlementService (paper_settlements 생성)
  → LedgerService (PENDING_SETTLEMENT 원장 기록)

PaperSettlementSettledEvent
  → LedgerService (SETTLEMENT_COMPLETE 원장 반영, cash 갱신)

SubscriptionActivatedEvent
  → CreatorEarningsService (strategy 구독이면 수익 적립)

BrokerageOrderFilledEvent
  → BrokerageSettlementService (T+2 정산 예약)
```

### 원장(Ledger) 이벤트 타입 확장

기존 `ledger_events.event_type`에 아래 타입 추가:

| event_type | 발생 시점 |
|------------|---------|
| `PAPER_SETTLEMENT_PENDING` | 체결 직후, 정산 대기 |
| `PAPER_SETTLEMENT_COMPLETE` | T+2 정산 완료, 실잔고 반영 |
| `CREATOR_EARNING_CREDITED` | 전략 구독 수익 적립 |
| `CREATOR_PAYOUT_REQUESTED` | 출금 요청 |
| `CREATOR_PAYOUT_PAID` | 출금 지급 완료 |
| `SUBSCRIPTION_PAYMENT` | 구독료 결제 |
| `BROKERAGE_SETTLEMENT` | 실거래 정산 수신 |

### 환경변수

```bash
# 구독료 PG Mock (기본 true — 로컬 개발)
PG_MOCK_ENABLED=true
PG_TOSS_SECRET_KEY=...         # 프로덕션: 토스페이먼츠 시크릿 키

# 증권사 연동 Mock (기본 true — 로컬 개발)
BROKERAGE_MOCK_ENABLED=true
KIS_APP_KEY=...                # 프로덕션: KIS Open API 앱 키
KIS_APP_SECRET=...             # 프로덕션: KIS Open API 앱 시크릿
```

---

## 구현 순서 (권장)

```
1. V27 마이그레이션  — 모든 정산 테이블 DDL
2. ① 페이퍼 정산    — PaperSettlementService + Spring Batch Job
3. ③ 구독료 정산    — PgClient 인터페이스 + MockPgClient + SubscriptionService
4. ② 전략 수익 분배 — CreatorEarningsService (③ 완료 후)
5. ④ 증권사 정산    — BrokerageClient 인터페이스 + MockBrokerageClient
6. 원장 이벤트 타입 확장 및 UI 연동
```

---

## 관련 문서

- [data-model.md](data-model.md) — 기존 DB 스키마 (paper_accounts, fills, ledger_events)
- [architecture.md](architecture.md) — 모듈 경계 및 이벤트 흐름
- [decisions/011-order-saga-orchestration.md](decisions/011-order-saga-orchestration.md) — 주문 Saga 패턴
- [decisions/008-outbox-pattern-spring-modulith.md](decisions/008-outbox-pattern-spring-modulith.md) — 이벤트 발행 패턴
