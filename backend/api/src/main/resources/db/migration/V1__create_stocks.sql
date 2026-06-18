CREATE TABLE stocks (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(20)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    market      VARCHAR(20)  NOT NULL,
    exchange    VARCHAR(50)  NOT NULL,
    sector      VARCHAR(100),
    industry    VARCHAR(100),
    country     VARCHAR(10)  NOT NULL DEFAULT 'KR',
    currency    VARCHAR(10)  NOT NULL DEFAULT 'KRW',
    listed_at   DATE,
    delisted_at DATE,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_stocks_symbol_market UNIQUE (symbol, market)
);

CREATE TABLE stock_aliases (
    id         BIGSERIAL PRIMARY KEY,
    stock_id   BIGINT       NOT NULL REFERENCES stocks(id) ON DELETE CASCADE,
    alias      VARCHAR(200) NOT NULL,
    alias_type VARCHAR(50)  NOT NULL
);

CREATE INDEX idx_stocks_symbol ON stocks(symbol);
CREATE INDEX idx_stocks_name ON stocks(name);
CREATE INDEX idx_stocks_market ON stocks(market);
CREATE INDEX idx_stocks_is_active ON stocks(is_active);
CREATE INDEX idx_stock_aliases_stock_id ON stock_aliases(stock_id);
CREATE INDEX idx_stock_aliases_alias ON stock_aliases(alias);
