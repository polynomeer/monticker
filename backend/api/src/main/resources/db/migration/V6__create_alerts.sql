CREATE TABLE alert_rules (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users(id),
    stock_id       BIGINT       REFERENCES stocks(id),
    rule_type      VARCHAR(50)  NOT NULL,
    condition_json JSONB        NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE alert_histories (
    id              BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT       NOT NULL REFERENCES alert_rules(id),
    stock_id        BIGINT       REFERENCES stocks(id),
    triggered_at    TIMESTAMPTZ  NOT NULL,
    message         TEXT         NOT NULL,
    payload_json    JSONB,
    delivery_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_alert_rules_user_id   ON alert_rules (user_id);
CREATE INDEX idx_alert_rules_stock_id  ON alert_rules (stock_id);
CREATE INDEX idx_alert_histories_rule  ON alert_histories (rule_id);
