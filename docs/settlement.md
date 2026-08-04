# monticker — 정산 시스템 설계

> Read this when: 정산 도메인 코드를 작성하거나, 새 정산 플로우를 추가하거나, 외부 연동 인터페이스를 설계할 때.

---

## 개요

monticker의 정산 시스템은 4개의 독립 도메인으로 구성된다.

| # | 도메인 | 목적 | 실거래 여부 |
|---|--------|------|------------|
| ① | **페이퍼트레이딩 정산** | 모의투자 체결 건의 T+2 결제·원장 반영 | 모의 (실머니 없음) |
| ② | **전략 마켓 수익 분배** | 전략 구독 수익을 제작자 계정에 적립·출금 | 서비스 내 포인트 |
| ③ | **구독료 정산** | 플랜별 월 이용료 PG 결제 및 청구서 관리 | PG 연동 (Mock) |
| ④ | **실거래 증권사 정산** | 실제 주식 매매를 증권사 API에 위임·정산 수신 | 증권사 연동 (Mock) |

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
  → APPROVED: 외부 지급 처리 후 PAID
```

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

monticker는 3단계 구독 플랜을 제공한다. 월 구독료는 PG(Payment Gateway)를 통해 결제되며, 결제 성공 시 구독이 활성화된다. 로컬 개발 환경에서는 PG Mock이 항상 결제 성공을 반환한다.

### 구독 플랜

| 플랜 | 월 금액 | 주요 기능 |
|------|---------|----------|
| `FREE` | 0원 | 기본 시세 조회, 관심종목 10개, 알림 3개 |
| `PRO` | 9,900원 | 무제한 알림, AI 요약, 포트폴리오 분석 |
| `QUANT` | 29,900원 | Quant Lab 전체, 전략 마켓 수익 분배, 백테스트 우선 실행 |

### 결제 플로우

```
사용자 플랜 선택
  → POST /api/subscription/subscribe
  → PgClient.requestPayment() (실제: 토스페이먼츠/아임포트 / Mock: 즉시 SUCCESS)
  → payment_records 저장 (status=SUCCESS | FAILED)
  → SUCCESS: user_subscriptions 갱신, 구독 활성화 이벤트 발행
  → FAILED:  결제 실패 응답

월 갱신 (배치, 매월 1일)
  → 만료 예정 구독 조회
  → PgClient.requestPayment() 재시도
  → 실패 3회: 구독 FREE 다운그레이드
```

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
    fun requestPayment(request: PaymentRequest): PaymentResult
    fun requestRefund(pgTransactionId: String, amount: BigDecimal): RefundResult
}

// 로컬 개발용 Mock (SOCIAL_MOCK_ENABLED=true 와 동일한 방식)
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "true")
@Primary
class MockPgClient : PgClient {
    override fun requestPayment(request: PaymentRequest) =
        PaymentResult(success = true, pgTransactionId = "mock_${UUID.randomUUID()}")
    override fun requestRefund(pgTransactionId: String, amount: BigDecimal) =
        RefundResult(success = true)
}

// 실제 PG 연동 (토스페이먼츠 예시)
@ConditionalOnProperty("app.pg.mock.enabled", havingValue = "false", matchIfMissing = true)
class TossPgClient(
    @Value("\${pg.toss.secret-key}") private val secretKey: String,
) : PgClient { ... }
```

### API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/subscription/plans` | 플랜 목록 조회 |
| `GET` | `/api/subscription/me` | 내 구독 현황 |
| `POST` | `/api/subscription/subscribe` | 플랜 구독 (결제) |
| `POST` | `/api/subscription/cancel` | 구독 해지 |
| `GET` | `/api/subscription/payments` | 결제 이력 |

---

## ④ 실거래 증권사 정산 (Mock)

### 개념

실제 주식 매매는 증권사 API(한국투자증권 KIS Open API 기준)를 통해 위임한다. 실계좌와 모의계좌를 함께 지원하며, 체결 후 T+2 영업일에 증권사로부터 정산 내역을 수신한다. 로컬 개발 환경에서는 KIS API 응답 구조를 그대로 모사한 Mock 서버를 사용한다.

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
  → KIS 정산 API 폴링 (매일 16:30 KST)
  → brokerage_settlements 저장
  → 원장 이벤트 발행 (BROKERAGE_SETTLEMENT)
```

### Mock 서버 구조

KIS API를 호출하는 `BrokerageClient` 인터페이스를 정의하고, Mock 구현체가 인메모리에서 즉시 체결 결과를 반환한다.

```kotlin
interface BrokerageClient {
    fun issueToken(appKey: String, appSecret: String): BrokerageToken
    fun submitOrder(token: BrokerageToken, req: BrokerageOrderRequest): BrokerageOrderResult
    fun getOrderStatus(token: BrokerageToken, orderId: String): BrokerageOrderStatus
    fun getSettlements(token: BrokerageToken, date: LocalDate): List<BrokerageSettlement>
    fun getBalance(token: BrokerageToken): BrokerageBalance
}

@ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "true")
@Primary
class MockBrokerageClient : BrokerageClient {
    // 시장가는 현재가 ±0.05% 슬리피지 적용 후 즉시 체결
    // 지정가는 현재가 도달 시 체결 (인메모리 OrderBook 시뮬레이션)
    // T+2 정산은 현재일+2 날짜로 settlement 레코드 생성
}

@ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "false", matchIfMissing = true)
class KisBrokerageClient(
    @Value("\${kis.base-url:https://openapi.koreainvestment.com:9443}") private val baseUrl: String,
) : BrokerageClient { ... }
```

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
