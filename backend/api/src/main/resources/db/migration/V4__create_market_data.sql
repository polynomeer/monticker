CREATE TABLE price_ticks (
    stock_id   BIGINT         NOT NULL,
    price      NUMERIC(18, 4) NOT NULL,
    volume     BIGINT         NOT NULL,
    trade_time TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (stock_id, trade_time)
);

CREATE TABLE candles_1m (
    stock_id    BIGINT         NOT NULL,
    open        NUMERIC(18, 4) NOT NULL,
    high        NUMERIC(18, 4) NOT NULL,
    low         NUMERIC(18, 4) NOT NULL,
    close       NUMERIC(18, 4) NOT NULL,
    volume      BIGINT         NOT NULL DEFAULT 0,
    candle_time TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (stock_id, candle_time)
);

CREATE TABLE candles_1d (
    stock_id    BIGINT         NOT NULL,
    open        NUMERIC(18, 4) NOT NULL,
    high        NUMERIC(18, 4) NOT NULL,
    low         NUMERIC(18, 4) NOT NULL,
    close       NUMERIC(18, 4) NOT NULL,
    volume      BIGINT         NOT NULL DEFAULT 0,
    candle_time TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (stock_id, candle_time)
);

CREATE INDEX idx_price_ticks_stock_time ON price_ticks (stock_id, trade_time DESC);
CREATE INDEX idx_candles_1m_stock_time  ON candles_1m  (stock_id, candle_time DESC);
CREATE INDEX idx_candles_1d_stock_time  ON candles_1d  (stock_id, candle_time DESC);
