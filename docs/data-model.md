# monticker — Data Model

> Read this when: writing a Flyway migration, designing a query, or adding a new entity.

> **정본은 마이그레이션이다.** 이 문서는 핵심 도메인의 스키마와 **설계 의도**를 설명하고,
> 컬럼 단위의 최신 사실은 `backend/api/src/main/resources/db/migration/V*.sql`(현재 V1–V49)이 갖는다.
> 두 곳이 다르면 마이그레이션이 옳다 — 문서는 뒤처질 수 있다.
>
> 아래에 DDL이 실려 있지 않은 테이블은 **[테이블 인덱스](#테이블-인덱스)** 에서 어느 마이그레이션이
> 만들었는지 찾을 수 있다.

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

## Matching Engine Tables (V15 — done)

### orders

체결 엔진에 접수된 주문. LIMIT 주문은 체결 전까지 Order Book에 대기한다.

```sql
CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT         NOT NULL REFERENCES users(id),
    stock_id     BIGINT         NOT NULL REFERENCES stocks(id),
    side         VARCHAR(4)     NOT NULL CHECK (side IN ('BUY','SELL')),
    order_type   VARCHAR(10)    NOT NULL CHECK (order_type IN ('MARKET','LIMIT')),
    quantity     INTEGER        NOT NULL CHECK (quantity > 0),
    limit_price  NUMERIC(18,4),          -- MARKET 주문은 NULL
    filled_qty   INTEGER        NOT NULL DEFAULT 0,
    avg_fill_price NUMERIC(18,4),
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    -- PENDING | PARTIALLY_FILLED | FILLED | CANCELLED | REJECTED
    reject_reason TEXT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user_status ON orders (user_id, status, created_at DESC);
CREATE INDEX idx_orders_stock_status ON orders (stock_id, status);
```

Order 상태 전이:
```
MARKET 주문: PENDING → FILLED (즉시, 슬리피지 반영)
             PENDING → PARTIALLY_FILLED → FILLED
LIMIT 주문:  PENDING → (Order Book 대기) → PARTIALLY_FILLED → FILLED
             PENDING → CANCELLED (사용자 취소)
             PENDING → REJECTED  (리스크 한도 초과)
```

### fills

개별 체결 이벤트. 하나의 주문이 여러 번의 fills를 가질 수 있다(부분체결).

```sql
CREATE TABLE fills (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT         NOT NULL REFERENCES orders(id),
    user_id      BIGINT         NOT NULL REFERENCES users(id),
    stock_id     BIGINT         NOT NULL REFERENCES stocks(id),
    side         VARCHAR(4)     NOT NULL,
    quantity     INTEGER        NOT NULL,
    fill_price   NUMERIC(18,4)  NOT NULL,
    amount       NUMERIC(18,4)  NOT NULL,  -- quantity × fill_price
    fee          NUMERIC(18,4)  NOT NULL DEFAULT 0,
    filled_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_fills_order    ON fills (order_id);
CREATE INDEX idx_fills_user     ON fills (user_id, filled_at DESC);
```

### risk_limits

사용자별 리스크 한도 설정.

```sql
CREATE TABLE risk_limits (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT         NOT NULL REFERENCES users(id) UNIQUE,
    daily_loss_limit_pct    NUMERIC(5,2)   NOT NULL DEFAULT 3.00,   -- 일일 손실 한도 %
    concentration_limit_pct NUMERIC(5,2)   NOT NULL DEFAULT 30.00,  -- 종목당 최대 비중 %
    var_limit_pct           NUMERIC(5,2)   NOT NULL DEFAULT 5.00,   -- 95% VaR 한도 %
    max_position_count      INTEGER        NOT NULL DEFAULT 10,      -- 최대 보유 종목 수
    max_hourly_orders       INTEGER        NOT NULL DEFAULT 5,       -- 1시간 최대 주문 수
    is_active               BOOLEAN        NOT NULL DEFAULT true,
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);
```

### risk_check_logs

모든 리스크 체크 결과 기록. 거부된 주문의 사유 추적에 사용.

```sql
CREATE TABLE risk_check_logs (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id),
    stock_id     BIGINT      REFERENCES stocks(id),
    side         VARCHAR(4),
    quantity     INTEGER,
    approved     BOOLEAN     NOT NULL,
    blocked_by   VARCHAR(100),
    checks_json  JSONB,      -- 각 규칙 통과/실패 상세
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_check_logs_user ON risk_check_logs (user_id, created_at DESC);
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

## Investment Wallet Tables (V14 — done)

### paper_trades.fill_id (V44 — done)

[ADR-047](decisions/047-single-execution-path-for-paper-account.md): `paper_trades`는 계좌의 실행 기록이고 매칭 엔진 체결
(`fills`)과 `fill_id`(UNIQUE, nullable)로 1:1 링크된다. ADR-047 이전 구 페이퍼 경로의 거래는 NULL.

### ledger_events

모든 잔고 변화의 append-only 원장. ~~잔고는 이벤트를 replay해서 계산한다.~~ **잔고의 authoritative
source는 `paper_accounts.cash` 컬럼**이고 원장은 병렬 감사 기록이다 — 둘의 정합성은 일일 대사가
확인한다([ADR-043](decisions/043-ledger-pagination-and-reconciliation.md)). 실제 스키마(V14+V43)는 아래
초안과 다르다: 컬럼은 `paper_trade_id`(FK 없음 — paper 경로는 `paper_trades.id`, 매칭 엔진 경로는
`fills.id`), 인덱스는 `(user_id, created_at DESC)` + **`(user_id, id DESC)`**(커서 페이징) +
`(paper_trade_id) WHERE NOT NULL`(영수증). 조회는 `id` 커서로 페이징한다.

```sql
CREATE TABLE ledger_events (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT         NOT NULL REFERENCES users(id),
    event_type   VARCHAR(30)    NOT NULL,
    -- DEPOSIT | WITHDRAWAL | CASH_RESERVED | CASH_UNRESERVED
    -- FILL | PARTIAL_FILL | FEE | SETTLEMENT
    amount       NUMERIC(18, 4) NOT NULL,  -- 양수 = 증가, 음수 = 감소
    balance_after NUMERIC(18,4),           -- 스냅샷 (replay 가속화용)
    paper_order_id BIGINT       REFERENCES paper_orders(id),
    stock_id     BIGINT         REFERENCES stocks(id),
    description  TEXT,
    metadata_json JSONB,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_events_user_time ON ledger_events (user_id, created_at DESC);
CREATE INDEX idx_ledger_events_order     ON ledger_events (paper_order_id);
```

Event type flow per order:
```
매수 주문:  CASH_RESERVED → PARTIAL_FILL (여러 번 가능) → FILL → FEE → SETTLEMENT
매도 주문:  FILL → FEE → CASH_UNRESERVED → SETTLEMENT
취소:       CASH_UNRESERVED
```

### ledger_snapshots (V43 — done)

일일 대사 스냅샷. 목적은 replay 가속이 아니라 **드리프트 감지와 "언제부터" 좁히기**. 불변식
`account_cash + reserved_cash = 10,000,000 + ledger_sum`. `last_event_id`부터 델타만 더한다.

```sql
CREATE TABLE ledger_snapshots (
    user_id        BIGINT        NOT NULL REFERENCES users(id),
    as_of_date     DATE          NOT NULL,
    ledger_sum     NUMERIC(18,4) NOT NULL,             -- 현금 영향 이벤트 타입만의 누적 합
    account_cash   NUMERIC(18,4) NOT NULL,
    reserved_cash  NUMERIC(18,4) NOT NULL DEFAULT 0,   -- 미체결 BUY 주문 limit_price × 잔량
    last_event_id  BIGINT        NOT NULL,
    mismatch       BOOLEAN       NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, as_of_date)
);
```

### wallet_snapshots (초안 — 미구현)

`ledger_events` replay 가속화를 위한 일별 스냅샷. ADR-043이 `ledger_snapshots`로 대체했다.

```sql
CREATE TABLE wallet_snapshots (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT         NOT NULL REFERENCES users(id),
    snapshot_date        DATE           NOT NULL,
    available_cash       NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reserved_cash        NUMERIC(18, 4) NOT NULL DEFAULT 0,
    settlement_pending   NUMERIC(18, 4) NOT NULL DEFAULT 0,
    holdings_value       NUMERIC(18, 4) NOT NULL DEFAULT 0,
    total_assets         NUMERIC(18, 4) NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (user_id, snapshot_date)
);
```

### order_emotion_tags

주문 시점의 감정 태그. 나중에 수익률과 연결해서 투자 습관 분석에 사용한다.

```sql
CREATE TABLE order_emotion_tags (
    id             BIGSERIAL PRIMARY KEY,
    paper_order_id BIGINT      NOT NULL REFERENCES paper_orders(id),
    user_id        BIGINT      NOT NULL REFERENCES users(id),
    emotion        VARCHAR(30) NOT NULL,
    -- CONFIDENT | ANXIOUS | FOLLOWING | NEWS_BASED | FOMO | LONG_TERM
    -- INTUITION | REBALANCING | AVERAGING_DOWN | OTHER
    memo           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_emotion_tags_user ON order_emotion_tags (user_id, created_at DESC);
```

Emotion types:
```
CONFIDENT      확신
ANXIOUS        불안
FOLLOWING      따라삼
NEWS_BASED     뉴스 보고
FOMO           급등 놓칠까 봐
LONG_TERM      장기 투자
INTUITION      직감
REBALANCING    비중 조절
AVERAGING_DOWN 물타기
```

### investment_behavior_scores

투자 행동 점수와 생존 점수의 일별 기록.

```sql
CREATE TABLE investment_behavior_scores (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT    NOT NULL REFERENCES users(id),
    score_date         DATE      NOT NULL,
    behavior_score     INTEGER,  -- 0~100, 투자 습관 점수
    survival_score     INTEGER,  -- 0~100, 리스크 관리 점수
    score_breakdown    JSONB,    -- 항목별 점수 내역
    feedback_json      JSONB,    -- 좋았던 점 / 주의할 점
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, score_date)
);
```

---

## Watch Rule Tables (V49 — done, [ADR-051](decisions/051-event-triggered-paper-orders.md))

탐지된 이벤트를 모의투자 주문으로 연결하는 규칙과 그 발동 기록. **모의투자 계좌 전용**이다.

### watch_rules

```sql
CREATE TABLE watch_rules (
    id                   BIGSERIAL   PRIMARY KEY,
    user_id              BIGINT      NOT NULL REFERENCES users(id),
    stock_id             BIGINT      NOT NULL REFERENCES stocks(id),
    event_type           VARCHAR(50) NOT NULL,   -- PRICE_SPIKE / PRICE_DROP / VOLUME_SURGE
    side                 VARCHAR(4)  NOT NULL,   -- BUY / SELL
    quantity             INTEGER     NOT NULL,
    min_importance_score INTEGER     NOT NULL DEFAULT 0,   -- 이 값 미만이면 발동 안 함
    cooldown_sec         INTEGER     NOT NULL DEFAULT 600, -- 직전 체결 후 재발동 금지 구간
    is_active            BOOLEAN     NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_watch_rules_side       CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_watch_rules_quantity   CHECK (quantity > 0),
    CONSTRAINT ck_watch_rules_importance CHECK (min_importance_score BETWEEN 0 AND 100),
    CONSTRAINT ck_watch_rules_cooldown   CHECK (cooldown_sec >= 0)
);

-- 컨슈머의 조회 경로
CREATE INDEX idx_watch_rules_stock_event ON watch_rules (stock_id, event_type) WHERE is_active;
```

### watch_rule_executions

성공뿐 아니라 거부·건너뜀도 남긴다 — 사용자가 "왜 안 샀는지" 볼 수 있어야 한다.

```sql
CREATE TABLE watch_rule_executions (
    id             BIGSERIAL   PRIMARY KEY,
    watch_rule_id  BIGINT      NOT NULL REFERENCES watch_rules(id) ON DELETE CASCADE,
    user_id        BIGINT      NOT NULL REFERENCES users(id),
    stock_event_id BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL,   -- EXECUTED / REJECTED / SKIPPED
    order_id       BIGINT,
    fill_price     NUMERIC(18,4),
    quantity       INTEGER,
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_watch_rule_exec_status CHECK (status IN ('EXECUTED', 'REJECTED', 'SKIPPED'))
);

-- 멱등 키 — 아웃박스 재전달·컨슈머 리밸런싱에서 기록이 두 줄 생기지 않게 한다.
CREATE UNIQUE INDEX ux_watch_rule_exec_idempotency
    ON watch_rule_executions (watch_rule_id, stock_event_id);
```

> `orders.idempotency_key`(V48)도 같은 ADR에서 추가됐다. **중복 체결을 막는 것은 그쪽**이고,
> 이 유니크는 기록 중복만 막는다. 둘 다 필요하다.

---

## Quant Analytics Tables (V16 — done)

### detected_patterns

차트 패턴 인식 결과. `stock_events`와 별도로 패턴 상세 정보를 보존한다.

```sql
CREATE TABLE detected_patterns (
    id              BIGSERIAL PRIMARY KEY,
    stock_id        BIGINT       NOT NULL REFERENCES stocks(id),
    pattern_type    VARCHAR(30)  NOT NULL,
    -- HEAD_AND_SHOULDERS | DOUBLE_BOTTOM | DOUBLE_TOP | ASCENDING_TRIANGLE | DESCENDING_TRIANGLE
    confidence_score INTEGER     NOT NULL,  -- 0~100, 패턴 완성도
    swing_points_json JSONB      NOT NULL,  -- 패턴을 구성한 swing point 좌표
    detected_at     TIMESTAMPTZ  NOT NULL,
    candle_from     TIMESTAMPTZ  NOT NULL,  -- 패턴 시작 캔들 시각
    candle_to       TIMESTAMPTZ  NOT NULL,  -- 패턴 완성 캔들 시각
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_detected_patterns_stock ON detected_patterns (stock_id, detected_at DESC);
```

### regime_history

시장 국면 분류 이력. 일별로 종목/지수의 국면을 기록한다.

```sql
CREATE TABLE regime_history (
    id           BIGSERIAL PRIMARY KEY,
    stock_id     BIGINT      REFERENCES stocks(id),  -- null = 시장 전체(KOSPI/NASDAQ)
    market       VARCHAR(20),                         -- stock_id가 null일 때 사용
    regime_date  DATE        NOT NULL,
    regime       VARCHAR(20) NOT NULL,  -- BULL | BEAR | SIDEWAYS | HIGH_VOL
    adx          NUMERIC(8,4),
    volatility   NUMERIC(8,4),
    trend_slope  NUMERIC(10,6),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (stock_id, market, regime_date)
);
```

### tax_harvesting_logs

세금 최적화 시뮬레이션 기록 (실제 신고용 아님, 교육용).

```sql
CREATE TABLE tax_harvesting_logs (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT         NOT NULL REFERENCES users(id),
    simulated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    realized_gain_ytd   NUMERIC(18,4)  NOT NULL,  -- 올해 실현 이익 합계
    candidates_json     JSONB          NOT NULL,  -- 손실 매도 후보 종목 + 평가손실액
    estimated_tax_saving NUMERIC(18,4),
    tax_rate_assumed     NUMERIC(5,4)  NOT NULL DEFAULT 0.22
);
```

### portfolio_optimizations

포트폴리오 최적화 실행 기록.

```sql
CREATE TABLE portfolio_optimizations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    target_return   NUMERIC(8,4),
    universe_json   JSONB          NOT NULL,  -- 최적화 대상 종목 ID 목록
    weights_json    JSONB          NOT NULL,  -- 결과: stockId → weight
    expected_return NUMERIC(8,4),
    expected_risk   NUMERIC(8,4),
    frontier_json   JSONB,                    -- 효율적 프론티어 전체 곡선
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
```

---

## TimescaleDB Tables

### ~~price_ticks~~ — 제거됨 (V42, [ADR-041](decisions/041-timescale-hypertable-promotion.md))

원시 틱은 Postgres에 저장하지 않는다. 이 테이블은 V4에서 만들어진 뒤 **한 행도 쓰인 적이 없었고**
(쓰기 코드의 호출부 0건, 읽는 곳 0건) V42가 비어 있음을 확인하고 드롭했다. 틱은 Kafka `market.ticks`
(retention 6h)에만 흐르며, 장기 보관이 필요해지면 Parquet/오브젝트 스토리지 경로를 만든다
(scale-out-plan §6.2.2, Phase 2).

### candles — TimescaleDB hypertable (V42)

`candles_1m`(chunk 7일, 14일 후 압축)·`candles_1d`(chunk 30일, 90일 후 압축). 압축은 `segmentby = stock_id`.
`compress_after`는 upsert 윈도우보다 뒤여야 한다 — 압축 chunk는 UPDATE 불가([ADR-021](decisions/021-candles-1d-realtime-upsert.md)의
당일 upsert와의 계약). 보존 정책은 없다(캔들은 삭제하지 않는다). Continuous Aggregate는 채택하지 않았다.

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

## Quant Lab Tables (V13 — done)

### rule_sets

Stores user-defined investment rulesets. `rule_definition_encrypted` is never sent to clients.

```sql
CREATE TABLE rule_sets (
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT       NOT NULL REFERENCES users(id),
    name                      VARCHAR(200) NOT NULL,
    description               TEXT,
    version                   INTEGER      NOT NULL DEFAULT 1,
    status                    VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    -- DRAFT | BACKTESTED | FORWARD_TESTING | RUNNING | PUBLISHED | ARCHIVED
    visibility                VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    -- PRIVATE | LINK_ONLY | MARKET
    rule_definition_encrypted TEXT         NOT NULL,  -- AES-256, never sent to client
    rule_set_fingerprint      VARCHAR(64)  NOT NULL,  -- SHA-256(normalised definition)
    universe_json             JSONB        NOT NULL,  -- market, filters, sectors
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rule_sets_user     ON rule_sets (user_id);
CREATE INDEX idx_rule_sets_status   ON rule_sets (status);
CREATE INDEX idx_rule_sets_fingerprint ON rule_sets (rule_set_fingerprint);
```

Status transitions:
```
DRAFT → BACKTESTED → FORWARD_TESTING → RUNNING → PUBLISHED
                                              ↘ ARCHIVED
```

### rule_set_versions

Immutable version history. Each `PUT /rulesets/{id}` creates a new row.

```sql
CREATE TABLE rule_set_versions (
    id                        BIGSERIAL PRIMARY KEY,
    rule_set_id               BIGINT      NOT NULL REFERENCES rule_sets(id),
    version                   INTEGER     NOT NULL,
    rule_definition_encrypted TEXT        NOT NULL,
    rule_set_fingerprint      VARCHAR(64) NOT NULL,
    change_summary            TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rule_set_id, version)
);
```

### backtest_results

One row per backtest run. Linked to a specific ruleset version.

```sql
CREATE TABLE backtest_results (
    id                   BIGSERIAL PRIMARY KEY,
    rule_set_id          BIGINT         NOT NULL REFERENCES rule_sets(id),
    rule_set_version     INTEGER        NOT NULL,
    start_date           DATE           NOT NULL,
    end_date             DATE           NOT NULL,
    universe_snapshot    JSONB          NOT NULL,  -- frozen at run time
    total_return         NUMERIC(10, 4),
    annual_return        NUMERIC(10, 4),
    mdd                  NUMERIC(10, 4),           -- maximum drawdown (%)
    win_rate             NUMERIC(10, 4),
    profit_factor        NUMERIC(10, 4),
    trade_count          INTEGER,
    avg_holding_days     NUMERIC(8, 2),
    max_consecutive_loss INTEGER,
    benchmark_symbol     VARCHAR(20),              -- KOSPI | NASDAQ
    benchmark_return     NUMERIC(10, 4),
    excess_return        NUMERIC(10, 4),
    commission_rate      NUMERIC(8, 4) NOT NULL DEFAULT 0.015,
    tax_rate             NUMERIC(8, 4) NOT NULL DEFAULT 0.2,
    slippage_rate        NUMERIC(8, 4) NOT NULL DEFAULT 0.1,
    reliability_score    VARCHAR(1),               -- A | B | C | D
    reliability_notes    JSONB,                    -- penalised factors
    phase_performance    JSONB,                    -- bull / bear / sideways breakdown
    survivorship_bias    BOOLEAN NOT NULL DEFAULT false,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Reliability score criteria:
```
A: 5y+ data, 100+ trades, forward tested 90d, no over-optimisation flag
B: 3y+ data, 50+ trades, forward tested 30d
C: < 3y or < 30 trades or param changed > 10 times
D: out-of-sample gap > 20%, or < 10 trades
```

### forward_test_results

Aggregated daily summary of live-market forward testing.

```sql
CREATE TABLE forward_test_results (
    id              BIGSERIAL PRIMARY KEY,
    rule_set_id     BIGINT         NOT NULL REFERENCES rule_sets(id),
    test_date       DATE           NOT NULL,
    signal_count    INTEGER        NOT NULL DEFAULT 0,
    paper_trade_count INTEGER      NOT NULL DEFAULT 0,
    daily_return    NUMERIC(10, 4),
    cumulative_return NUMERIC(10, 4),
    win_rate        NUMERIC(10, 4),
    mdd             NUMERIC(10, 4),
    vs_backtest_win_rate_diff NUMERIC(10, 4),  -- forward - backtest win rate
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (rule_set_id, test_date)
);
```

### quant_signals

Individual signal events emitted by the Rule Engine.

```sql
CREATE TABLE quant_signals (
    id           BIGSERIAL PRIMARY KEY,
    rule_set_id  BIGINT      NOT NULL REFERENCES rule_sets(id),
    stock_id     BIGINT      NOT NULL REFERENCES stocks(id),
    direction    VARCHAR(10) NOT NULL, -- BUY | SELL | WATCH
    signal_time  TIMESTAMPTZ NOT NULL,
    mode         VARCHAR(20) NOT NULL, -- FORWARD_TEST | AUTO_TRADE | LIVE
    result_json  JSONB,                -- post-signal price outcome (filled later)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_quant_signals_ruleset_time ON quant_signals (rule_set_id, signal_time DESC);
CREATE INDEX idx_quant_signals_stock        ON quant_signals (stock_id);
```

---

### strategy_products

Lists a ruleset on the Strategy Market.

```sql
CREATE TABLE strategy_products (
    id               BIGSERIAL PRIMARY KEY,
    rule_set_id      BIGINT       NOT NULL REFERENCES rule_sets(id) UNIQUE,
    seller_id        BIGINT       NOT NULL REFERENCES users(id),
    title            VARCHAR(200) NOT NULL,
    summary          TEXT,              -- public description (no ruleset detail)
    strategy_type    VARCHAR(50),       -- MOMENTUM | MEAN_REVERSION | BREAKOUT | ...
    risk_level       VARCHAR(10),       -- LOW | MEDIUM | HIGH
    price_type       VARCHAR(20)  NOT NULL, -- FREE | MONTHLY | ONE_TIME
    price            NUMERIC(10, 2),
    access_type      VARCHAR(20)  NOT NULL, -- SIGNAL_ONLY | PAPER_TRADE | TEMPLATE
    disclosure_level VARCHAR(20)  NOT NULL DEFAULT 'SUMMARY',
    -- SUMMARY (성과 요약만) | DESCRIPTION (전략 설명) | FULL (룰셋 공개)
    review_status    VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    -- DRAFT | PENDING | APPROVED | REJECTED | SUSPENDED
    compliance_agreed_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### strategy_subscriptions

```sql
CREATE TABLE strategy_subscriptions (
    id           BIGSERIAL PRIMARY KEY,
    buyer_id     BIGINT       NOT NULL REFERENCES users(id),
    product_id   BIGINT       NOT NULL REFERENCES strategy_products(id),
    access_level VARCHAR(20)  NOT NULL, -- SIGNAL_ONLY | PAPER_TRADE
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,           -- null = lifetime
    UNIQUE (buyer_id, product_id)
);
```

### strategy_badges

```sql
CREATE TABLE strategy_badges (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT      NOT NULL REFERENCES strategy_products(id),
    badge_type   VARCHAR(50) NOT NULL,
    -- BACKTEST_VERIFIED | FORWARD_30D | FORWARD_90D | LOW_MDD | LOW_FREQ
    -- COMMISSION_APPLIED | SLIPPAGE_APPLIED | OVER_OPTIMISED_WARNING
    awarded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## Redis Key Schema

```
stock:price:{market}:{symbol}           → 최신 시세 JSON (STRING)
orderbook:{symbol}                      → KIS 실시간 호가 JSON (STRING, TTL 30s)
alert:cooldown:{ruleId}                 → 알림 쿨다운 플래그 (STRING, TTL 600s)
alert:rules:changed                     → 알림 룰 인메모리 인덱스 무효화 신호 (ADR-044)
ratelimit:{prefix}:{userId}             → @RateLimited 카운터 (TTL = window)
idempotency:{userId}:{key}              → X-Idempotency-Key 응답 캐시 (TTL 24h)
```

한때 여기 적혀 있었으나 **코드에 존재한 적이 없거나 폐기된** 키들:

| 키 | 왜 없는가 |
|---|---|
| `stream:market:ticks`, `stream:events:detected` | Redis Streams 대신 Kafka를 택했다([ADR-004](decisions/004-redis-streams-over-kafka.md)) |
| `news:dedup:{urlHash}` | Bloom Filter로 대체([ADR-010](decisions/010-bloom-filter-news-deduplication.md)) |
| `signal:forward:{ruleSetId}:{date}` | 포워드 테스트 신호는 `quant_signals` 테이블에 쓴다([ADR-024](decisions/024-quant-lab-forward-test.md)) |
| `wallet:snapshot:{userId}` | 지갑 스냅샷 캐시는 도입되지 않았다 — 원장 replay + `ledger_snapshots`(V43)가 그 역할 |

---

## Entity Relationship (simplified)

```
users
  ├── watchlist_groups → watchlist_items → stocks
  ├── alert_rules → alert_histories
  ├── portfolios → portfolio_positions → stocks
  ├── simulation_accounts → simulation_trades → stocks
  ├── orders ──────────────────────────── Matching Engine (V15)
  │     └── fills → stocks
  └── risk_limits / risk_check_logs
  ├── rule_sets ──────────────────────────────────── Quant Lab
  │     ├── rule_set_versions
  │     ├── backtest_results
  │     ├── forward_test_results
  │     ├── quant_signals → stocks
  │     └── strategy_products
  │           ├── strategy_subscriptions ← users (buyer)
  │           └── strategy_badges
  │
  └── (Investment Wallet — V14) ────────────
        ├── ledger_events → paper_orders → stocks
        ├── wallet_snapshots
        ├── investment_behavior_scores
        └── paper_orders → order_emotion_tags

stocks
  ├── stock_aliases
  ├── stock_sector_mappings → sectors
  ├── stock_events  ◄── news_articles (via news_stock_mappings)
  │                 ◄── disclosures
  │                 ◄── user notes / simulation_trades
  │                 ◄── quant_signals
  └── candles_* (TimescaleDB hypertable, V42)
```

---

## 테이블 인덱스

전체 테이블과 그것을 만든 마이그레이션. **DDL 열이 '—' 인 것은 이 문서에 스키마가 없다** — 해당 마이그레이션 파일을 본다.
(Spring Batch 메타테이블 6종은 프레임워크가 만들므로 제외)

| 테이블 | 생성 | 이 문서에 DDL |
|---|---|---|
| `alert_histories` | V6 | ✅ |
| `alert_rules` | V6 | ✅ |
| `brokerage_accounts` | V27 | — |
| `brokerage_orders` | V27 (+V37 `broker_order_ref`, +V51 `PENDING_SUBMIT`/`UNKNOWN`·`client_order_id`·대조 컬럼 ADR-056, +V54 수동 확정, +V55 `status_synced_at` ADR-061, +V56 `cost_basis_price` ADR-062) | — |
| `brokerage_settlements` | V27 (+V55 `order_id` 유일 — 체결 하나에 정산 하나, ADR-061) | — |
| `candles_1d` | V4 | — |
| `candles_1m` | V4 | ✅ |
| `conditional_orders` | V38 | — |
| `creator_earnings` | V27 | — |
| `creator_payouts` | V27 | — |
| `detected_patterns` | V16 | ✅ |
| `device_tokens` | V9 | — |
| `event_publication` | V18 | — |
| `fills` | V15 | ✅ |
| `investment_behavior_scores` | V14 | ✅ |
| `investor_flow` | V29 | — |
| `ledger_events` | V14 | ✅ |
| `ledger_snapshots` | V43 | ✅ |
| `news_articles` | V8 | ✅ |
| `order_emotion_tags` | V14 | ✅ |
| `order_proposals` | V40 | — |
| `order_sagas` | V20 | — |
| `orders` | V15 | ✅ |
| `paper_accounts` | V11 | — |
| `paper_settlements` | V27 | — |
| `paper_trades` | V11 | — |
| `payment_records` | V27 | — |
| `portfolio_optimizations` | V16 | ✅ |
| `portfolio_positions` | V21 | ✅ |
| ~~`price_ticks`~~ | V4 → V42 제거 | 원시 틱은 Postgres에 저장하지 않는다 (ADR-041) |
| `quant_backtest_results` | V13 | — |
| `quant_forward_test_equity` | V33 | — |
| `quant_forward_tests` | V33 | — |
| `quant_signals` | V13 | ✅ |
| `realtime_price_coverage` | V53 | — 실시세 연결 종목, worker 공표·heartbeat (ADR-060) |
| `rebalance_execution_legs` | V39 | — |
| `rebalance_executions` | V39 | — |
| `rebalance_targets` | V39 | — |
| `refresh_tokens` | V7 | — |
| `regime_history` | V16 | ✅ |
| `risk_check_logs` | V15 | ✅ |
| `risk_limits` | V15 | ✅ |
| `rule_set_versions` | V13 | ✅ |
| `rule_sets` | V13 | ✅ |
| `stock_aliases` | V1 | ✅ |
| `stock_comment_reports` | V41 | — |
| `stock_comments` | V41 | — |
| `stock_events` | V5 | ✅ |
| `stock_fundamentals` | V30 | — |
| `stocks` | V1 | ✅ |
| `strategy_market` | V24 | — |
| `strategy_subscriptions` | V24 | ✅ |
| `subscription_plans` | V27 | — |
| `tax_harvesting_logs` | V16 | ✅ |
| `trading_halts` | V52 | — 실거래 킬 스위치·감사 이력, 행 삭제 없음 (ADR-057) |
| `user_billing_keys` | V32 | — |
| `user_subscriptions` | V27 | — |
| `users` | V2 | ✅ |
| `watch_rule_executions` | V49 | ✅ |
| `watch_rules` | V49 | ✅ |
| `watchlist_groups` | V3 | ✅ |
| `watchlist_items` | V3 | ✅ |
