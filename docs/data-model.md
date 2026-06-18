# monticker — Data Model

> Read this when: writing a Flyway migration, designing a query, or adding a new entity.

## Storage Assignment

| Store | What goes here |
|-------|---------------|
| PostgreSQL | Business entities (users, stocks, news, events, alerts, portfolio, simulation) |
| TimescaleDB | Time-series price data (ticks, candles) |
| Redis | Latest price cache, pub/sub, streams, cooldown, dedup keys |

---

## PostgreSQL Tables

### users

```sql
CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255),
    nickname       VARCHAR(100) NOT NULL,
    provider       VARCHAR(50)  NOT NULL DEFAULT 'LOCAL', -- LOCAL | GOOGLE | KAKAO
    role           VARCHAR(50)  NOT NULL DEFAULT 'USER',  -- USER | ADMIN
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### user_settings

```sql
CREATE TABLE user_settings (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT      NOT NULL REFERENCES users(id),
    notification_enabled  BOOLEAN     NOT NULL DEFAULT true,
    theme                 VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    language              VARCHAR(10) NOT NULL DEFAULT 'ko',
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

### stocks

```sql
CREATE TABLE stocks (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(20)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    market      VARCHAR(20)  NOT NULL, -- KOSPI | KOSDAQ | NASDAQ | NYSE
    exchange    VARCHAR(50)  NOT NULL,
    sector      VARCHAR(100),
    industry    VARCHAR(100),
    country     VARCHAR(10)  NOT NULL DEFAULT 'KR',
    currency    VARCHAR(10)  NOT NULL DEFAULT 'KRW',
    listed_at   DATE,
    delisted_at DATE,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (symbol, market)
);
```

### stock_aliases

```sql
CREATE TABLE stock_aliases (
    id         BIGSERIAL PRIMARY KEY,
    stock_id   BIGINT       NOT NULL REFERENCES stocks(id),
    alias      VARCHAR(200) NOT NULL,
    alias_type VARCHAR(50)  NOT NULL -- ABBR | BRAND | TICKER_OLD
);
```

### sectors

```sql
CREATE TABLE sectors (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    parent_id  BIGINT REFERENCES sectors(id)
);

CREATE TABLE stock_sector_mappings (
    stock_id   BIGINT NOT NULL REFERENCES stocks(id),
    sector_id  BIGINT NOT NULL REFERENCES sectors(id),
    PRIMARY KEY (stock_id, sector_id)
);
```

---

### news_articles

```sql
CREATE TABLE news_articles (
    id                BIGSERIAL PRIMARY KEY,
    title             TEXT        NOT NULL,
    content_summary   TEXT,
    source            VARCHAR(100) NOT NULL,
    url               TEXT        NOT NULL UNIQUE,
    published_at      TIMESTAMPTZ NOT NULL,
    sentiment_score   NUMERIC(5, 4), -- -1.0 ~ 1.0
    importance_score  INTEGER,       -- 0 ~ 100
    ai_summary        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE news_stock_mappings (
    id              BIGSERIAL PRIMARY KEY,
    news_id         BIGINT         NOT NULL REFERENCES news_articles(id),
    stock_id        BIGINT         NOT NULL REFERENCES stocks(id),
    relevance_score NUMERIC(5, 4)  NOT NULL,
    matched_reason  VARCHAR(50)    NOT NULL -- NAME | ALIAS | TICKER | SECTOR | AI
);
```

---

### disclosures

```sql
CREATE TABLE disclosures (
    id               BIGSERIAL PRIMARY KEY,
    stock_id         BIGINT       NOT NULL REFERENCES stocks(id),
    title            TEXT         NOT NULL,
    disclosure_type  VARCHAR(100) NOT NULL, -- EARNINGS | SUPPLY_CONTRACT | RIGHTS_ISSUE | ...
    source_url       TEXT         NOT NULL,
    published_at     TIMESTAMPTZ  NOT NULL,
    summary          TEXT,
    importance_score INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

Disclosure types:
```
EARNINGS             실적 발표
SUPPLY_CONTRACT      공급 계약
RIGHTS_ISSUE         유상증자
BONUS_ISSUE          무상증자
CONVERTIBLE_BOND     전환사채
TREASURY_BUYBACK     자사주 매입
MAJOR_SHAREHOLDER    최대주주 변경
MERGER               합병 / 분할
LAWSUIT              소송
DELISTING_RISK       상장폐지 관련
```

---

### stock_events

**Central table. All data sources converge here.**

```sql
CREATE TABLE stock_events (
    id               BIGSERIAL PRIMARY KEY,
    stock_id         BIGINT       NOT NULL REFERENCES stocks(id),
    event_type       VARCHAR(50)  NOT NULL,
    title            VARCHAR(300) NOT NULL,
    description      TEXT,
    event_time       TIMESTAMPTZ  NOT NULL,
    importance_score INTEGER      NOT NULL DEFAULT 0, -- 0 ~ 100
    sentiment_score  NUMERIC(5, 4),                   -- -1.0 ~ 1.0
    source_type      VARCHAR(50),  -- NEWS | DISCLOSURE | SYSTEM | USER
    source_id        BIGINT,
    metadata_json    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_events_stock_time ON stock_events (stock_id, event_time DESC);
CREATE INDEX idx_stock_events_type       ON stock_events (event_type);
CREATE INDEX idx_stock_events_importance ON stock_events (importance_score DESC);
```

Event types:
```
PRICE_SPIKE
PRICE_DROP
VOLUME_SURGE
NEWS_PUBLISHED
DISCLOSURE_PUBLISHED
SECTOR_MOVE
SENTIMENT_CHANGE
USER_MEMO
SIMULATION_TRADE
```

### event_relations

Links events that are causally or temporally related.

```sql
CREATE TABLE event_relations (
    id             BIGSERIAL PRIMARY KEY,
    event_id       BIGINT      NOT NULL REFERENCES stock_events(id),
    related_id     BIGINT      NOT NULL REFERENCES stock_events(id),
    relation_type  VARCHAR(50) NOT NULL -- CAUSED_BY | CONCURRENT | FOLLOWED_BY
);
```

---

### watchlist_groups / watchlist_items

```sql
CREATE TABLE watchlist_groups (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    name       VARCHAR(100) NOT NULL,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE watchlist_items (
    id           BIGSERIAL PRIMARY KEY,
    group_id     BIGINT       NOT NULL REFERENCES watchlist_groups(id),
    stock_id     BIGINT       NOT NULL REFERENCES stocks(id),
    memo         TEXT,
    target_price NUMERIC(18, 4),
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (group_id, stock_id)
);
```

---

### alert_rules / alert_histories

```sql
CREATE TABLE alert_rules (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id),
    stock_id       BIGINT      REFERENCES stocks(id), -- null = market-wide
    rule_type      VARCHAR(50) NOT NULL, -- PRICE | RATE | VOLUME | NEWS | DISCLOSURE | COMPOSITE
    condition_json JSONB       NOT NULL,
    is_active      BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE alert_histories (
    id              BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT      NOT NULL REFERENCES alert_rules(id),
    stock_id        BIGINT      REFERENCES stocks(id),
    triggered_at    TIMESTAMPTZ NOT NULL,
    message         TEXT        NOT NULL,
    payload_json    JSONB,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' -- PENDING | SENT | FAILED
);
```

---

### portfolios / portfolio_positions

```sql
CREATE TABLE portfolios (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    name          VARCHAR(100) NOT NULL,
    base_currency VARCHAR(10)  NOT NULL DEFAULT 'KRW',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE portfolio_positions (
    id            BIGSERIAL PRIMARY KEY,
    portfolio_id  BIGINT         NOT NULL REFERENCES portfolios(id),
    stock_id      BIGINT         NOT NULL REFERENCES stocks(id),
    quantity      NUMERIC(18, 6) NOT NULL,
    average_price NUMERIC(18, 4) NOT NULL,
    memo          TEXT,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (portfolio_id, stock_id)
);
```

---

### simulation_trades

```sql
CREATE TABLE simulation_accounts (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT         NOT NULL REFERENCES users(id) UNIQUE,
    balance        NUMERIC(18, 4) NOT NULL DEFAULT 10000000, -- 1000만원 기본
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE simulation_trades (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT         NOT NULL REFERENCES users(id),
    stock_id         BIGINT         NOT NULL REFERENCES stocks(id),
    trade_type       VARCHAR(10)    NOT NULL, -- BUY | SELL
    quantity         NUMERIC(18, 6) NOT NULL,
    price            NUMERIC(18, 4) NOT NULL,
    traded_at        TIMESTAMPTZ    NOT NULL,
    reason           TEXT,
    confidence_score INTEGER,       -- user self-rating 1~5
    snapshot_json    JSONB,         -- market state at trade time
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
```

---

## TimescaleDB Tables

### price_ticks

```sql
CREATE TABLE price_ticks (
    stock_id   BIGINT         NOT NULL,
    price      NUMERIC(18, 4) NOT NULL,
    volume     BIGINT         NOT NULL,
    trade_time TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (stock_id, trade_time)
);
SELECT create_hypertable('price_ticks', 'trade_time');
```

### candles

```sql
CREATE TABLE candles_1m (
    stock_id    BIGINT         NOT NULL,
    open        NUMERIC(18, 4) NOT NULL,
    high        NUMERIC(18, 4) NOT NULL,
    low         NUMERIC(18, 4) NOT NULL,
    close       NUMERIC(18, 4) NOT NULL,
    volume      BIGINT         NOT NULL,
    candle_time TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (stock_id, candle_time)
);
SELECT create_hypertable('candles_1m', 'candle_time');

-- Same structure for candles_5m, candles_1d
```

---

## Redis Key Schema

```
stock:price:{market}:{symbol}           → latest price hash
stock:candle:1m:{market}:{symbol}       → latest 1m candle
stock:orderbook:{market}:{symbol}       → orderbook snapshot
watchlist:prices:user:{userId}          → hash of stock prices for a user's watchlist
news:dedup:{urlHash}                    → SET member for deduplication (TTL 7d)
alert:cooldown:{ruleId}                 → key existence = in cooldown (TTL = cooldown seconds)
stream:market:ticks                     → Redis Stream for tick events
stream:events:detected                  → Redis Stream for detected stock_events
```

---

## Entity Relationship (simplified)

```
users
  ├── watchlist_groups → watchlist_items → stocks
  ├── alert_rules → alert_histories
  ├── portfolios → portfolio_positions → stocks
  └── simulation_accounts → simulation_trades → stocks

stocks
  ├── stock_aliases
  ├── stock_sector_mappings → sectors
  ├── stock_events  ◄── news_articles (via news_stock_mappings)
  │                 ◄── disclosures
  │                 ◄── price_ticks (system generated)
  │                 ◄── user notes / simulation_trades
  └── price_ticks / candles_* (TimescaleDB)
```
