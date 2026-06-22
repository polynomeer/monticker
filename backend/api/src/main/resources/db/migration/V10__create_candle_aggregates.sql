-- Continuous Aggregate: 1분봉 (price_ticks → candles_1m_cagg)
CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1m_cagg
WITH (timescaledb.continuous) AS
SELECT
    stock_id,
    time_bucket('1 minute', trade_time) AS candle_time,
    first(price, trade_time)            AS open,
    max(price)                          AS high,
    min(price)                          AS low,
    last(price, trade_time)             AS close,
    sum(volume)                         AS volume
FROM price_ticks
GROUP BY stock_id, time_bucket('1 minute', trade_time)
WITH NO DATA;

-- Continuous Aggregate: 일봉 (price_ticks → candles_1d_cagg)
CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1d_cagg
WITH (timescaledb.continuous) AS
SELECT
    stock_id,
    time_bucket('1 day', trade_time) AS candle_time,
    first(price, trade_time)         AS open,
    max(price)                       AS high,
    min(price)                       AS low,
    last(price, trade_time)          AS close,
    sum(volume)                      AS volume
FROM price_ticks
GROUP BY stock_id, time_bucket('1 day', trade_time)
WITH NO DATA;
