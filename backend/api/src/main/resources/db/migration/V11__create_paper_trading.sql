CREATE TABLE paper_accounts (
    id         BIGSERIAL     PRIMARY KEY,
    user_id    BIGINT        NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    cash       NUMERIC(18,4) NOT NULL DEFAULT 10000000,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE paper_trades (
    id        BIGSERIAL     PRIMARY KEY,
    user_id   BIGINT        NOT NULL REFERENCES users(id),
    stock_id  BIGINT        NOT NULL REFERENCES stocks(id),
    side      VARCHAR(4)    NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity  INTEGER       NOT NULL CHECK (quantity > 0),
    price     NUMERIC(18,4) NOT NULL,
    amount    NUMERIC(18,4) NOT NULL,
    traded_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_paper_trades_user  ON paper_trades (user_id, traded_at DESC);
CREATE INDEX idx_paper_trades_stock ON paper_trades (user_id, stock_id);
