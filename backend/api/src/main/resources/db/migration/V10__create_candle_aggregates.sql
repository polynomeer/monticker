-- Continuous Aggregate views for candle data
-- Only created when price_ticks is already a TimescaleDB hypertable.
-- Falls back gracefully on plain PostgreSQL or before hypertable conversion.

DO $$
BEGIN
  -- Check if price_ticks is a hypertable
  IF EXISTS (
    SELECT 1 FROM timescaledb_information.hypertables
    WHERE hypertable_name = 'price_ticks'
  ) THEN
    -- 1분봉 CAgg
    IF NOT EXISTS (
      SELECT 1 FROM timescaledb_information.continuous_aggregates
      WHERE view_name = 'candles_1m_cagg'
    ) THEN
      EXECUTE $sql$
        CREATE MATERIALIZED VIEW candles_1m_cagg
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
        WITH NO DATA
      $sql$;
    END IF;

    -- 일봉 CAgg
    IF NOT EXISTS (
      SELECT 1 FROM timescaledb_information.continuous_aggregates
      WHERE view_name = 'candles_1d_cagg'
    ) THEN
      EXECUTE $sql$
        CREATE MATERIALIZED VIEW candles_1d_cagg
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
        WITH NO DATA
      $sql$;
    END IF;

    -- Refresh policies
    IF EXISTS (
      SELECT 1 FROM timescaledb_information.continuous_aggregates
      WHERE view_name = 'candles_1m_cagg'
    ) THEN
      PERFORM add_continuous_aggregate_policy(
        'candles_1m_cagg',
        start_offset => INTERVAL '2 hours',
        end_offset   => INTERVAL '1 minute',
        schedule_interval => INTERVAL '1 minute',
        if_not_exists => TRUE
      );
      PERFORM add_continuous_aggregate_policy(
        'candles_1d_cagg',
        start_offset => INTERVAL '3 days',
        end_offset   => INTERVAL '1 hour',
        schedule_interval => INTERVAL '1 hour',
        if_not_exists => TRUE
      );
    END IF;

  ELSE
    RAISE NOTICE 'price_ticks is not a hypertable — skipping Continuous Aggregate creation';
  END IF;

EXCEPTION
  WHEN others THEN
    RAISE NOTICE 'Continuous Aggregate creation skipped: %', SQLERRM;
END $$;
