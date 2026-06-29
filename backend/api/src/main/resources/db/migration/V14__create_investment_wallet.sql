CREATE TABLE ledger_events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    event_type      VARCHAR(30)    NOT NULL,
    amount          NUMERIC(18,4)  NOT NULL,
    balance_after   NUMERIC(18,4),
    paper_trade_id  BIGINT         REFERENCES paper_trades(id),
    stock_id        BIGINT         REFERENCES stocks(id),
    description     TEXT,
    metadata_json   JSONB,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_events_user_time ON ledger_events (user_id, created_at DESC);

CREATE TABLE order_emotion_tags (
    id              BIGSERIAL PRIMARY KEY,
    paper_trade_id  BIGINT      NOT NULL REFERENCES paper_trades(id) UNIQUE,
    user_id         BIGINT      NOT NULL REFERENCES users(id),
    emotion         VARCHAR(30) NOT NULL,
    memo            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_emotion_tags_user ON order_emotion_tags (user_id, created_at DESC);

CREATE TABLE investment_behavior_scores (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT    NOT NULL REFERENCES users(id),
    score_date      DATE      NOT NULL,
    behavior_score  INTEGER,
    survival_score  INTEGER,
    score_breakdown JSONB,
    feedback_json   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, score_date)
);
