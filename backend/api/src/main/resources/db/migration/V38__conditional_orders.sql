-- ADR-032 — 조건부 주문(스탑로스/익절/OCO). brokerage_orders와 별개 테이블이다:
-- brokerage_orders는 "이미 브로커에 제출된 주문"만을 위한 CHECK 제약(order_type MARKET/LIMIT,
-- status SUBMITTED~REJECTED)이 있어 "아직 제출 안 된 대기 중 조건"을 끼워 넣기 부적합하다.
-- 조건 충족 시 기존 brokerage_orders에 진짜 주문 행을 만들고 executed_order_id로 연결한다.
CREATE TABLE IF NOT EXISTS conditional_orders (
    id               BIGSERIAL       PRIMARY KEY,
    user_id          BIGINT          NOT NULL REFERENCES users(id),
    account_id       BIGINT          NOT NULL REFERENCES brokerage_accounts(id),
    stock_id         BIGINT          NOT NULL REFERENCES stocks(id),
    symbol           VARCHAR(20)     NOT NULL,
    side             VARCHAR(4)      NOT NULL CHECK (side IN ('BUY','SELL')),
    trigger_type     VARCHAR(20)     NOT NULL CHECK (trigger_type IN ('STOP_LOSS','TAKE_PROFIT','PRICE_ABOVE','PRICE_BELOW')),
    trigger_price    NUMERIC(18,4)   NOT NULL CHECK (trigger_price > 0),
    order_type       VARCHAR(10)     NOT NULL CHECK (order_type IN ('MARKET','LIMIT')),
    limit_price      NUMERIC(18,4),
    quantity         INTEGER         NOT NULL CHECK (quantity > 0),
    -- ADR-032 — 한쪽이 발동/실패하면 같은 그룹의 ACTIVE 형제를 자동 취소한다(OCO). 단일
    -- 트리거 주문은 NULL.
    oco_group_id     UUID,
    status           VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                                     CHECK (status IN ('ACTIVE','TRIGGERED','EXECUTED','CANCELLED','EXPIRED','FAILED')),
    fail_reason      TEXT,
    executed_order_id BIGINT         REFERENCES brokerage_orders(id),
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    triggered_at     TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ConditionalOrderEvaluator의 hot path — 틱마다 "이 종목의 ACTIVE 조건부 주문"을 조회한다
-- (AlertEvaluator.fetchRulesForStock와 동일 패턴).
CREATE INDEX IF NOT EXISTS idx_conditional_orders_stock_active
    ON conditional_orders (stock_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_conditional_orders_user
    ON conditional_orders (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_conditional_orders_oco_group
    ON conditional_orders (oco_group_id) WHERE oco_group_id IS NOT NULL;
