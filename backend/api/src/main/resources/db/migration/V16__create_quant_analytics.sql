CREATE TABLE detected_patterns (
    id                BIGSERIAL PRIMARY KEY,
    stock_id          BIGINT       NOT NULL REFERENCES stocks(id),
    pattern_type      VARCHAR(30)  NOT NULL,
    confidence_score  INTEGER      NOT NULL,
    swing_points_json JSONB        NOT NULL,
    detected_at       TIMESTAMPTZ  NOT NULL,
    candle_from       TIMESTAMPTZ  NOT NULL,
    candle_to         TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_detected_patterns_stock ON detected_patterns (stock_id, detected_at DESC);

CREATE TABLE regime_history (
    id          BIGSERIAL PRIMARY KEY,
    stock_id    BIGINT      REFERENCES stocks(id),
    market      VARCHAR(20),
    regime_date DATE        NOT NULL,
    regime      VARCHAR(20) NOT NULL,
    adx         NUMERIC(8,4),
    volatility  NUMERIC(8,4),
    trend_slope NUMERIC(10,6),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (stock_id, market, regime_date)
);

CREATE TABLE tax_harvesting_logs (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT         NOT NULL REFERENCES users(id),
    simulated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    realized_gain_ytd    NUMERIC(18,4)  NOT NULL,
    candidates_json      JSONB          NOT NULL,
    estimated_tax_saving NUMERIC(18,4),
    tax_rate_assumed     NUMERIC(5,4)   NOT NULL DEFAULT 0.22
);

CREATE TABLE portfolio_optimizations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    target_return   NUMERIC(8,4),
    universe_json   JSONB          NOT NULL,
    weights_json    JSONB          NOT NULL,
    expected_return NUMERIC(8,4),
    expected_risk   NUMERIC(8,4),
    frontier_json   JSONB,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
