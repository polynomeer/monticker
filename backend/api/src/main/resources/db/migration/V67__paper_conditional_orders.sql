-- ADR-075 — 모의투자 조건부 주문(익절·손절·가격 도달, OCO, 체결 시 자동 등록).
-- 실전 conditional_orders(V38)와 별개 테이블이다: 그쪽은 brokerage_accounts·brokerage_orders를 참조하고
-- 발동이 실브로커 주문이다. 이 테이블의 발동은 오직 매칭 엔진(모의 계좌) 시장가 주문이며 실브로커로 갈 경로가 없다.
CREATE TABLE IF NOT EXISTS paper_conditional_orders (
    id                BIGSERIAL     PRIMARY KEY,
    user_id           BIGINT        NOT NULL REFERENCES users(id),
    stock_id          BIGINT        NOT NULL REFERENCES stocks(id),
    side              VARCHAR(4)    NOT NULL CHECK (side IN ('BUY','SELL')),
    trigger_type      VARCHAR(20)   NOT NULL CHECK (trigger_type IN ('STOP_LOSS','TAKE_PROFIT','PRICE_ABOVE','PRICE_BELOW')),
    trigger_price     NUMERIC(18,4) NOT NULL CHECK (trigger_price > 0),
    quantity          INTEGER       NOT NULL CHECK (quantity > 0),
    -- 같은 그룹의 한쪽이 체결되면 나머지를 취소한다(OCO). 단일 조건은 NULL.
    oco_group_id      UUID,
    -- "체결 시 자동 등록" — 이 매칭 주문(orders.id)이 체결되면 WAITING_PARENT → ACTIVE, 취소되면 CANCELLED.
    parent_order_id   BIGINT        REFERENCES orders(id),
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('WAITING_PARENT','ACTIVE','EXECUTED','CANCELLED','FAILED')),
    fail_reason       TEXT,
    executed_order_id BIGINT        REFERENCES orders(id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    triggered_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 스위퍼 hot path — ACTIVE만 본다.
CREATE INDEX IF NOT EXISTS idx_paper_cond_active ON paper_conditional_orders (stock_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_paper_cond_user ON paper_conditional_orders (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_paper_cond_parent ON paper_conditional_orders (parent_order_id) WHERE status = 'WAITING_PARENT';
CREATE INDEX IF NOT EXISTS idx_paper_cond_oco ON paper_conditional_orders (oco_group_id) WHERE oco_group_id IS NOT NULL;
