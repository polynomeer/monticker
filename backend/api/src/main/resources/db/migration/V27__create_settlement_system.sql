-- ──────────────────────────────────────────────────────────────────────────────
-- V27 : 정산 시스템 테이블 (docs/settlement.md 기준)
--
-- ① paper_settlements       — 페이퍼트레이딩 T+2 정산
-- ② creator_earnings        — 전략 마켓 제작자 수익 적립
-- ③ creator_payouts         — 제작자 수익 출금 요청
-- ④ subscription_plans      — 구독 플랜 정의
-- ⑤ user_subscriptions      — 사용자 구독 현황
-- ⑥ payment_records         — PG 결제 이력
-- ⑦ brokerage_accounts      — 증권사 계좌 연동
-- ⑧ brokerage_orders        — 실거래 주문
-- ⑨ brokerage_settlements   — 실거래 T+2 정산
-- ──────────────────────────────────────────────────────────────────────────────

-- ① 페이퍼트레이딩 T+2 정산
CREATE TABLE IF NOT EXISTS paper_settlements (
    id           BIGSERIAL       PRIMARY KEY,
    fill_id      BIGINT          NOT NULL UNIQUE REFERENCES fills(id),
    user_id      BIGINT          NOT NULL REFERENCES users(id),
    stock_id     BIGINT          NOT NULL REFERENCES stocks(id),
    side         VARCHAR(4)      NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity     INTEGER         NOT NULL CHECK (quantity > 0),
    fill_price   NUMERIC(18,4)   NOT NULL,
    gross_amount NUMERIC(18,4)   NOT NULL,
    fee          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    tax          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    net_amount   NUMERIC(18,4)   NOT NULL,
    status       VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING','SETTLED','FAILED')),
    settle_date  DATE            NOT NULL,
    settled_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_paper_settlements_user_status
    ON paper_settlements (user_id, status, settle_date);
CREATE INDEX IF NOT EXISTS idx_paper_settlements_settle_date
    ON paper_settlements (settle_date, status);

-- ② 전략 마켓 제작자 수익 적립
CREATE TABLE IF NOT EXISTS creator_earnings (
    id            BIGSERIAL       PRIMARY KEY,
    creator_id    BIGINT          NOT NULL REFERENCES users(id),
    strategy_id   BIGINT          NOT NULL REFERENCES strategy_market(id),
    subscriber_id BIGINT          NOT NULL REFERENCES users(id),
    payment_id    BIGINT          NOT NULL,       -- payment_records.id (순환참조 회피 — FK는 앱 레벨 보장)
    gross_amount  NUMERIC(18,4)   NOT NULL,
    platform_fee  NUMERIC(18,4)   NOT NULL,
    net_amount    NUMERIC(18,4)   NOT NULL,
    status        VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE'
                                  CHECK (status IN ('AVAILABLE','PAID_OUT','CANCELLED')),
    earned_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_creator_earnings_creator
    ON creator_earnings (creator_id, status);
CREATE INDEX IF NOT EXISTS idx_creator_earnings_strategy
    ON creator_earnings (strategy_id, earned_at DESC);

-- ③ 제작자 수익 출금 요청
CREATE TABLE IF NOT EXISTS creator_payouts (
    id              BIGSERIAL       PRIMARY KEY,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    amount          NUMERIC(18,4)   NOT NULL CHECK (amount > 0),
    bank_name       VARCHAR(50),
    account_number  VARCHAR(50),
    account_holder  VARCHAR(50),
    status          VARCHAR(20)     NOT NULL DEFAULT 'REQUESTED'
                                    CHECK (status IN ('REQUESTED','APPROVED','REJECTED','PAID')),
    reject_reason   TEXT,
    requested_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_creator_payouts_creator
    ON creator_payouts (creator_id, status);
CREATE INDEX IF NOT EXISTS idx_creator_payouts_status
    ON creator_payouts (status, requested_at DESC);

-- ④ 구독 플랜 정의
CREATE TABLE IF NOT EXISTS subscription_plans (
    id         BIGSERIAL       PRIMARY KEY,
    code       VARCHAR(20)     NOT NULL UNIQUE,   -- FREE | PRO | QUANT
    name       VARCHAR(50)     NOT NULL,
    price      NUMERIC(10,2)   NOT NULL DEFAULT 0,
    currency   VARCHAR(10)     NOT NULL DEFAULT 'KRW',
    features   JSONB           NOT NULL DEFAULT '[]',
    is_active  BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

INSERT INTO subscription_plans (code, name, price, features) VALUES
    ('FREE',  '무료',     0,      '["기본 시세 조회","관심종목 10개","알림 3개"]'),
    ('PRO',   'Pro',     9900,   '["무제한 알림","AI 요약","포트폴리오 분석"]'),
    ('QUANT', 'Quant',  29900,  '["Quant Lab 전체","전략 마켓 수익 분배","백테스트 우선 실행"]')
ON CONFLICT (code) DO NOTHING;

-- ⑤ 사용자 구독 현황
CREATE TABLE IF NOT EXISTS user_subscriptions (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL UNIQUE REFERENCES users(id),
    plan_id      BIGINT      NOT NULL REFERENCES subscription_plans(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE','EXPIRED','CANCELLED')),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_expires
    ON user_subscriptions (expires_at, status) WHERE status = 'ACTIVE';

-- ⑥ PG 결제 이력
CREATE TABLE IF NOT EXISTS payment_records (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL REFERENCES users(id),
    plan_id           BIGINT          NOT NULL REFERENCES subscription_plans(id),
    pg_provider       VARCHAR(30)     NOT NULL DEFAULT 'MOCK',
                                      -- MOCK | TOSS | IAMPORT
    pg_transaction_id VARCHAR(100),
    amount            NUMERIC(10,2)   NOT NULL,
    currency          VARCHAR(10)     NOT NULL DEFAULT 'KRW',
    status            VARCHAR(20)     NOT NULL
                                      CHECK (status IN ('SUCCESS','FAILED','REFUNDED','PENDING')),
    failure_reason    TEXT,
    paid_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_records_user
    ON payment_records (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_records_pg_tx
    ON payment_records (pg_transaction_id) WHERE pg_transaction_id IS NOT NULL;

-- ⑦ 증권사 계좌 연동
CREATE TABLE IF NOT EXISTS brokerage_accounts (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users(id),
    provider         VARCHAR(20) NOT NULL DEFAULT 'KIS',
                                 -- KIS | MOCK
    account_number   VARCHAR(50) NOT NULL,
    account_type     VARCHAR(20) NOT NULL DEFAULT 'REAL',
                                 -- REAL | DEMO
    access_token     TEXT,       -- AES-256 암호화 저장
    token_expires_at TIMESTAMPTZ,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    connected_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, provider, account_number)
);

CREATE INDEX IF NOT EXISTS idx_brokerage_accounts_user
    ON brokerage_accounts (user_id, is_active);

-- ⑧ 실거래 주문
CREATE TABLE IF NOT EXISTS brokerage_orders (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    account_id      BIGINT          NOT NULL REFERENCES brokerage_accounts(id),
    stock_id        BIGINT          REFERENCES stocks(id),
    symbol          VARCHAR(20)     NOT NULL,
    side            VARCHAR(4)      NOT NULL CHECK (side IN ('BUY','SELL')),
    order_type      VARCHAR(10)     NOT NULL CHECK (order_type IN ('MARKET','LIMIT')),
    quantity        INTEGER         NOT NULL CHECK (quantity > 0),
    limit_price     NUMERIC(18,4),
    filled_qty      INTEGER         NOT NULL DEFAULT 0,
    avg_fill_price  NUMERIC(18,4),
    pg_order_id     VARCHAR(100),   -- 증권사 주문 번호
    status          VARCHAR(20)     NOT NULL DEFAULT 'SUBMITTED'
                                    CHECK (status IN ('SUBMITTED','FILLED','PARTIALLY_FILLED','CANCELLED','REJECTED')),
    reject_reason   TEXT,
    submitted_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    filled_at       TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_brokerage_orders_user
    ON brokerage_orders (user_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_brokerage_orders_account_status
    ON brokerage_orders (account_id, status);

-- ⑨ 실거래 T+2 정산
CREATE TABLE IF NOT EXISTS brokerage_settlements (
    id           BIGSERIAL       PRIMARY KEY,
    user_id      BIGINT          NOT NULL REFERENCES users(id),
    account_id   BIGINT          NOT NULL REFERENCES brokerage_accounts(id),
    order_id     BIGINT          REFERENCES brokerage_orders(id),
    symbol       VARCHAR(20)     NOT NULL,
    side         VARCHAR(4)      NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity     INTEGER         NOT NULL CHECK (quantity > 0),
    fill_price   NUMERIC(18,4)   NOT NULL,
    gross_amount NUMERIC(18,4)   NOT NULL,
    fee          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    tax          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    net_amount   NUMERIC(18,4)   NOT NULL,
    settle_date  DATE            NOT NULL,
    settled_at   TIMESTAMPTZ,
    status       VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING','SETTLED')),
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_brokerage_settlements_user
    ON brokerage_settlements (user_id, settle_date DESC);
CREATE INDEX IF NOT EXISTS idx_brokerage_settlements_settle_date
    ON brokerage_settlements (settle_date, status);
