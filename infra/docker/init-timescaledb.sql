-- Run after migrations to convert tables to TimescaleDB hypertables
SELECT create_hypertable('price_ticks', 'trade_time', if_not_exists => TRUE);
SELECT create_hypertable('candles_1m',  'candle_time', if_not_exists => TRUE);
SELECT create_hypertable('candles_1d',  'candle_time', if_not_exists => TRUE);

-- Refresh policies (after CAgg views are created by Flyway V10)
-- These are idempotent — safe to run multiple times
DO $$
BEGIN
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
END $$;
