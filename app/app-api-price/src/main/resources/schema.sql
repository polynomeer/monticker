CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE IF NOT EXISTS price_history
(
    ticker_code VARCHAR(20) NOT NULL,
    "timestamp" TIMESTAMPTZ NOT NULL,
    price       BIGINT      NOT NULL,
    volume      BIGINT      NOT NULL,
    exchange    VARCHAR(20),
    currency    VARCHAR(10),
    source      VARCHAR(20)
);

ALTER TABLE price_history
    DROP CONSTRAINT IF EXISTS price_history_pkey;
DROP INDEX IF EXISTS ux_price_history_id;
DROP INDEX IF EXISTS ux_price_history_ticker_only;

ALTER TABLE price_history
    ADD CONSTRAINT price_history_pkey PRIMARY KEY (ticker_code, "timestamp");

SELECT create_hypertable('price_history', 'timestamp', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS ix_price_history_ticker_ts_desc
    ON price_history (ticker_code, "timestamp" DESC);
