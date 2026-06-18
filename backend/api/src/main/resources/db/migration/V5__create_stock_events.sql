CREATE TABLE stock_events (
    id               BIGSERIAL    PRIMARY KEY,
    stock_id         BIGINT       NOT NULL REFERENCES stocks(id),
    event_type       VARCHAR(50)  NOT NULL,
    title            VARCHAR(300) NOT NULL,
    description      TEXT,
    event_time       TIMESTAMPTZ  NOT NULL,
    importance_score INTEGER      NOT NULL DEFAULT 0,
    sentiment_score  NUMERIC(5,4),
    source_type      VARCHAR(50),
    source_id        BIGINT,
    metadata_json    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_events_stock_time       ON stock_events (stock_id, event_time DESC);
CREATE INDEX idx_stock_events_type             ON stock_events (event_type);
CREATE INDEX idx_stock_events_importance       ON stock_events (importance_score DESC);
CREATE INDEX idx_stock_events_stock_type_time  ON stock_events (stock_id, event_type, event_time DESC);

-- Prevent duplicate events for the same stock/type within the same minute
CREATE UNIQUE INDEX uq_stock_events_dedup
    ON stock_events (stock_id, event_type, date_trunc('minute', event_time));
