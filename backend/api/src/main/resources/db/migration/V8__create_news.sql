CREATE TABLE news_articles (
    id           BIGSERIAL    PRIMARY KEY,
    stock_id     BIGINT       REFERENCES stocks(id),
    title        TEXT         NOT NULL,
    description  TEXT,
    url          VARCHAR(1000) NOT NULL,
    source       VARCHAR(100),
    published_at TIMESTAMPTZ  NOT NULL,
    sentiment    VARCHAR(20),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_news_stock_id      ON news_articles (stock_id);
CREATE INDEX idx_news_published_at  ON news_articles (stock_id, published_at DESC);
CREATE UNIQUE INDEX uq_news_url     ON news_articles (url);
