CREATE TABLE rule_sets (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users(id),
    name                 VARCHAR(200) NOT NULL,
    description          TEXT,
    version              INTEGER      NOT NULL DEFAULT 1,
    status               VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    rule_definition      JSONB        NOT NULL,
    rule_set_fingerprint VARCHAR(64)  NOT NULL,
    universe_json        JSONB        NOT NULL DEFAULT '{}',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE rule_set_versions (
    id                   BIGSERIAL PRIMARY KEY,
    rule_set_id          BIGINT       NOT NULL REFERENCES rule_sets(id) ON DELETE CASCADE,
    version              INTEGER      NOT NULL,
    rule_definition      JSONB        NOT NULL,
    rule_set_fingerprint VARCHAR(64)  NOT NULL,
    change_summary       TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (rule_set_id, version)
);

CREATE TABLE quant_backtest_results (
    id                   BIGSERIAL PRIMARY KEY,
    rule_set_id          BIGINT         NOT NULL REFERENCES rule_sets(id),
    rule_set_version     INTEGER        NOT NULL,
    stock_id             BIGINT         NOT NULL REFERENCES stocks(id),
    start_date           DATE           NOT NULL,
    end_date             DATE           NOT NULL,
    initial_capital      NUMERIC(18,4)  NOT NULL,
    final_capital        NUMERIC(18,4)  NOT NULL,
    total_return         NUMERIC(10,4),
    annual_return        NUMERIC(10,4),
    mdd                  NUMERIC(10,4),
    win_rate             NUMERIC(10,4),
    profit_factor        NUMERIC(10,4),
    trade_count          INTEGER,
    avg_holding_days     NUMERIC(8,2),
    benchmark_return     NUMERIC(10,4),
    excess_return        NUMERIC(10,4),
    commission_rate      NUMERIC(8,4)   NOT NULL DEFAULT 0.015,
    slippage_rate        NUMERIC(8,4)   NOT NULL DEFAULT 0.1,
    reliability_score    VARCHAR(1),
    reliability_notes    JSONB,
    trades_json          JSONB,
    equity_curve_json    JSONB,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE quant_signals (
    id           BIGSERIAL PRIMARY KEY,
    rule_set_id  BIGINT      NOT NULL REFERENCES rule_sets(id),
    stock_id     BIGINT      NOT NULL REFERENCES stocks(id),
    direction    VARCHAR(10) NOT NULL,
    signal_time  TIMESTAMPTZ NOT NULL,
    mode         VARCHAR(20) NOT NULL DEFAULT 'FORWARD_TEST',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rule_sets_user ON rule_sets(user_id);
CREATE INDEX idx_quant_backtest_ruleset ON quant_backtest_results(rule_set_id);
CREATE INDEX idx_quant_signals_ruleset ON quant_signals(rule_set_id, signal_time DESC);
