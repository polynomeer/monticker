-- Run after migrations to convert tables to TimescaleDB hypertables
SELECT create_hypertable('price_ticks', 'trade_time', if_not_exists => TRUE);
SELECT create_hypertable('candles_1m',  'candle_time', if_not_exists => TRUE);
SELECT create_hypertable('candles_1d',  'candle_time', if_not_exists => TRUE);
