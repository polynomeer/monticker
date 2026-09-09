-- ADR-034 — 리밸런싱 실행 자동화(실브로커리지, 수동 실행). 계좌당 활성 목표 비중 하나
-- (rebalance_targets), 실행 이력(rebalance_executions)과 leg별 결과(rebalance_execution_legs).
CREATE TABLE IF NOT EXISTS rebalance_targets (
    id               BIGSERIAL       PRIMARY KEY,
    user_id          BIGINT          NOT NULL REFERENCES users(id),
    account_id       BIGINT          NOT NULL REFERENCES brokerage_accounts(id),
    -- symbol -> weight(0~1) JSON 맵. PortfolioOptimization.weightsJson과 같은 저장 관례.
    weights_json     JSONB           NOT NULL,
    threshold_pct    NUMERIC(5,2)    NOT NULL DEFAULT 5.00 CHECK (threshold_pct > 0),
    source           VARCHAR(20)     NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('OPTIMIZER','MANUAL')),
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 계좌당 활성 목표는 하나만 — 새로 저장하면 기존 행을 갱신(UPSERT)한다.
CREATE UNIQUE INDEX IF NOT EXISTS idx_rebalance_targets_account
    ON rebalance_targets (account_id);

CREATE TABLE IF NOT EXISTS rebalance_executions (
    id               BIGSERIAL       PRIMARY KEY,
    user_id          BIGINT          NOT NULL REFERENCES users(id),
    account_id       BIGINT          NOT NULL REFERENCES brokerage_accounts(id),
    target_id        BIGINT          NOT NULL REFERENCES rebalance_targets(id),
    status           VARCHAR(20)     NOT NULL DEFAULT 'EXECUTING'
                                     CHECK (status IN ('EXECUTING','COMPLETED','PARTIALLY_FAILED')),
    requested_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS rebalance_execution_legs (
    id                  BIGSERIAL       PRIMARY KEY,
    execution_id        BIGINT          NOT NULL REFERENCES rebalance_executions(id),
    stock_id            BIGINT          NOT NULL REFERENCES stocks(id),
    symbol              VARCHAR(20)     NOT NULL,
    side                VARCHAR(4)      NOT NULL CHECK (side IN ('BUY','SELL')),
    target_weight       NUMERIC(6,4)    NOT NULL,
    current_weight      NUMERIC(6,4)    NOT NULL,
    diff_pct            NUMERIC(6,4)    NOT NULL,
    quantity            INTEGER         NOT NULL CHECK (quantity > 0),
    status              VARCHAR(20)     NOT NULL CHECK (status IN ('EXECUTED','FAILED')),
    executed_order_id   BIGINT          REFERENCES brokerage_orders(id),
    fail_reason         TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rebalance_executions_user
    ON rebalance_executions (user_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_rebalance_execution_legs_execution
    ON rebalance_execution_legs (execution_id);
