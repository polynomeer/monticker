CREATE TABLE IF NOT EXISTS strategy_market (
    id              BIGSERIAL PRIMARY KEY,
    ruleset_id      VARCHAR(24) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    description     TEXT,
    subscribe_count INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS strategy_subscriptions (
    market_id  BIGINT NOT NULL REFERENCES strategy_market(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (market_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_strategy_market_subscribe_count ON strategy_market(subscribe_count DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_subscriptions_user ON strategy_subscriptions(user_id);
