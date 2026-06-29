CREATE TABLE orders (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT         NOT NULL REFERENCES users(id),
    stock_id       BIGINT         NOT NULL REFERENCES stocks(id),
    side           VARCHAR(4)     NOT NULL CHECK (side IN ('BUY','SELL')),
    order_type     VARCHAR(10)    NOT NULL CHECK (order_type IN ('MARKET','LIMIT')),
    quantity       INTEGER        NOT NULL CHECK (quantity > 0),
    limit_price    NUMERIC(18,4),
    filled_qty     INTEGER        NOT NULL DEFAULT 0,
    avg_fill_price NUMERIC(18,4),
    status         VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING','PARTIALLY_FILLED','FILLED','CANCELLED','REJECTED')),
    reject_reason  TEXT,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user_status  ON orders (user_id, status, created_at DESC);
CREATE INDEX idx_orders_stock_status ON orders (stock_id, status);

CREATE TABLE fills (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT         NOT NULL REFERENCES orders(id),
    user_id     BIGINT         NOT NULL REFERENCES users(id),
    stock_id    BIGINT         NOT NULL REFERENCES stocks(id),
    side        VARCHAR(4)     NOT NULL,
    quantity    INTEGER        NOT NULL,
    fill_price  NUMERIC(18,4)  NOT NULL,
    amount      NUMERIC(18,4)  NOT NULL,
    fee         NUMERIC(18,4)  NOT NULL DEFAULT 0,
    filled_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_fills_order ON fills (order_id);
CREATE INDEX idx_fills_user  ON fills (user_id, filled_at DESC);

CREATE TABLE risk_limits (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT        NOT NULL REFERENCES users(id) UNIQUE,
    daily_loss_limit_pct    NUMERIC(5,2)  NOT NULL DEFAULT 3.00,
    concentration_limit_pct NUMERIC(5,2)  NOT NULL DEFAULT 30.00,
    var_limit_pct           NUMERIC(5,2)  NOT NULL DEFAULT 5.00,
    max_position_count      INTEGER       NOT NULL DEFAULT 10,
    max_hourly_orders       INTEGER       NOT NULL DEFAULT 5,
    is_active               BOOLEAN       NOT NULL DEFAULT true,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE risk_check_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id),
    stock_id    BIGINT      REFERENCES stocks(id),
    side        VARCHAR(4),
    quantity    INTEGER,
    approved    BOOLEAN     NOT NULL,
    blocked_by  VARCHAR(100),
    checks_json JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_check_logs_user ON risk_check_logs (user_id, created_at DESC);
