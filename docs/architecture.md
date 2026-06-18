# monticker — Architecture

> Read this when: designing a module, adding a new table, wiring up a new worker, or making any infrastructure decision.

## Core Principle

monticker is **event-centric**, not price-centric.

```
External data sources
  → collectors / workers
  → Redis (latest price) + TimescaleDB (ticks / candles)
  → Event Detector
  → stock_events
  → REST / WebSocket API
  → chart timeline (web / mobile)
```

Start as **Modular Monolith + async workers + Redis + TimescaleDB**. Do not introduce microservices until there is a concrete scaling reason.

---

## System Overview

```
┌─────────────────────────────────────┐
│              Client                  │
│   Next.js Web / Mobile (future)      │
└──────────────┬──────────────────────┘
               │ REST / WebSocket
               ▼
┌─────────────────────────────────────┐
│         API Application              │
│     Spring Boot Modular Monolith     │
│                                      │
│  Auth / Stock / Market Data Query /  │
│  Watchlist / Event Timeline /        │
│  News / Disclosure / Alert /         │
│  Portfolio / Simulation / AI Insight │
└────────────┬────────────┬────────────┘
             │            │
             ▼            ▼
    ┌─────────────┐  ┌──────────────┐
    │ PostgreSQL  │  │    Redis     │
    │ +TimescaleDB│  │              │
    │             │  │ Latest price │
    │ Business /  │  │ Pub/Sub      │
    │ event /     │  │ Streams      │
    │ tick data   │  │ Rate limit   │
    └──────┬──────┘  └──────┬───────┘
           │                │
           ▼                ▼
┌─────────────────────────────────────┐
│           Async Workers              │
│                                      │
│  Market Data Collector               │
│  Candle Aggregator                   │
│  News Collector                      │
│  Disclosure Collector                │
│  Event Detector                      │
│  Sentiment Analyzer                  │
│  AI Summary Worker                   │
│  Alert Dispatcher                    │
└────────────────┬────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────┐
│        External Data Sources         │
│  Stock Price / News / Disclosure /   │
│  Index / Sector / AI Provider        │
└─────────────────────────────────────┘
```

---

## Tech Stack

### Backend

```
Language:    Java or Kotlin
Framework:   Spring Boot
Pattern:     Modular Monolith
API:         REST + WebSocket
Security:    Spring Security + JWT
ORM:         JPA + QueryDSL
Migration:   Flyway
Batch:       Spring Scheduler / Spring Batch
```

### Frontend

```
Framework:   Next.js
Language:    TypeScript
Server state: TanStack Query
Client state: Zustand
Chart:       Lightweight Charts
UI:          Tailwind CSS + shadcn/ui
Realtime:    WebSocket
```

Start with responsive web. Native app (React Native / Expo) comes later.

### Database

```
Business data:   PostgreSQL
Time-series:     TimescaleDB (price_ticks, candles_*)
Cache / Realtime: Redis
Search:          PostgreSQL FTS (MVP) → OpenSearch later
Object storage:  S3-compatible
```

### Infra

```
Local:      Docker Compose
Stage 1:    Single VM + Docker Compose
Stage 2:    AWS ECS / Naver Cloud
Stage 3:    Kubernetes
CI/CD:      GitHub Actions
Monitoring: Prometheus + Grafana
Logging:    Loki
Errors:     Sentry
Tracing:    OpenTelemetry
```

---

## Backend Module Boundaries

### Stock Module
Manages static stock metadata.

Tables: `stocks`, `stock_aliases`, `sectors`, `stock_sector_mappings`

### Market Data Module
Collects and serves price, tick, and candle data.

Tables: `price_ticks`, `candles_1m`, `candles_5m`, `candles_1d` (TimescaleDB)

```sql
-- TimescaleDB hypertable
CREATE TABLE price_ticks (
    stock_id   BIGINT         NOT NULL,
    price      NUMERIC(18, 4) NOT NULL,
    volume     BIGINT         NOT NULL,
    trade_time TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (stock_id, trade_time)
);
SELECT create_hypertable('price_ticks', 'trade_time');
```

### Event Timeline Module

**The most important module.** Centralizes all event types into a single queryable table for the chart timeline.

Tables: `stock_events`, `event_relations`

```
stock_events
├── id
├── stock_id
├── event_type         -- see enum below
├── title
├── description
├── event_time
├── importance_score
├── sentiment_score
├── source_type
├── source_id
├── metadata_json
└── created_at
```

Event types:
```
PRICE_SPIKE / PRICE_DROP
VOLUME_SURGE
NEWS_PUBLISHED
DISCLOSURE_PUBLISHED
SECTOR_MOVE
SENTIMENT_CHANGE
USER_MEMO
SIMULATION_TRADE
```

All data sources converge here:
```
news_articles  ──┐
disclosures    ──┼──► stock_events ──► chart timeline
price_ticks    ──┤
user_notes     ──┘
```

### News Module
Collects news, deduplicates, maps to stocks, extracts keywords, scores sentiment.

Mapping priority:
1. Exact stock name match
2. Alias match
3. Ticker match
4. Sector keyword match
5. AI relevance scoring

### Alert Module
Supports composite conditions, not just simple price thresholds.

Example rule:
```
If stock rises ≥2% in 5 min
AND volume is ≥3× same-time average
AND related news within 30 min
→ send alert
```

---

## Realtime Data Pipeline

```
External Price API
  │
  ▼
Market Collector
  ├── Redis latest price cache
  ├── TimescaleDB tick storage
  ├── Redis Stream → Candle Aggregator
  ├── Redis Stream → Event Detector
  └── Redis Stream → WebSocket Broadcaster
```

**MVP:** Redis Streams  
**Scale-up:** Kafka  
**Large-scale:** Kafka + Flink

---

## Event Detector Design

### Price Spike Score
```
current 5-min return
÷ 20-day same-time average return
= Price Spike Score
```
A 2% move on a large-cap is a strong signal; on a small-cap it may be normal. Use stock-specific baselines, not fixed thresholds.

### Volume Surge Ratio
```
current 5-min volume
÷ 20-day same-time 5-min average volume
= Volume Spike Ratio

1.5×  → weak signal
3×    → meaningful signal
5×+   → strong signal
```

### Event Importance Score
```
importance_score =
  price change score
  + volume score
  + news importance
  + disclosure importance
  + sector co-movement
  + user interest weight
```

Used for: home feed ranking, timeline sort, alert trigger, AI summary selection.

---

## Redis Key Conventions

```
stock:price:KR:005930
stock:orderbook:KR:005930
stock:candle:1m:KR:005930
watchlist:prices:user:{userId}
news:dedup:{hash}
alert:cooldown:{ruleId}
```

---

## API Design

### REST

```http
GET  /api/stocks/search?query=samsung
GET  /api/stocks/{stockId}
GET  /api/stocks/{stockId}/price
GET  /api/stocks/{stockId}/candles?interval=1m&from=&to=
GET  /api/stocks/{stockId}/events?from=&to=
GET  /api/stocks/{stockId}/news
GET  /api/stocks/{stockId}/disclosures

GET  /api/watchlists
POST /api/watchlists/groups
POST /api/watchlists/{groupId}/items
DELETE /api/watchlists/items/{itemId}

GET    /api/alerts/rules
POST   /api/alerts/rules
PATCH  /api/alerts/rules/{ruleId}
DELETE /api/alerts/rules/{ruleId}
GET    /api/alerts/histories

POST /api/simulations/trades
GET  /api/simulations/trades
GET  /api/simulations/performance
GET  /api/simulations/reviews
```

### WebSocket

```
/ws/market
/ws/stocks/{stockId}
/ws/watchlists/{watchlistId}
```

Price message:
```json
{
  "type": "PRICE_UPDATED",
  "stockId": 1,
  "symbol": "005930",
  "price": 71000,
  "changeRate": 2.16,
  "volume": 1839200,
  "timestamp": "2026-06-18T10:30:00+09:00"
}
```

Event message:
```json
{
  "type": "EVENT_DETECTED",
  "stockId": 1,
  "eventType": "VOLUME_SURGE",
  "title": "Volume spike detected",
  "importanceScore": 87,
  "timestamp": "2026-06-18T10:30:00+09:00"
}
```

---

## Frontend Structure

```
monticker-web/
├── app/
│   ├── page.tsx
│   ├── stocks/[symbol]/page.tsx
│   ├── watchlist/
│   ├── portfolio/
│   ├── simulation/
│   └── alerts/
├── components/
│   ├── chart/
│   │   ├── StockChart.tsx
│   │   ├── EventMarker.tsx
│   │   └── TimelinePanel.tsx
│   ├── stock/
│   ├── news/
│   └── common/
├── hooks/
│   ├── useStockPrice.ts
│   ├── useStockEvents.ts
│   ├── useWatchlist.ts
│   └── useWebSocket.ts
├── api/
│   ├── stockApi.ts
│   ├── watchlistApi.ts
│   ├── eventApi.ts
│   └── alertApi.ts
└── stores/
    ├── authStore.ts
    ├── realtimeStore.ts
    └── watchlistStore.ts
```

State management:
- Server state → TanStack Query
- Realtime state → Zustand
- Chart state → component-local
- Auth state → Auth Store

---

## Deployment — MVP

```
┌──────────────────────────┐
│     Nginx (SSL/proxy)     │
└────────────┬─────────────┘
             │
     ┌───────┴───────┐
     ▼               ▼
 Next.js         Spring Boot
   Web            API Server
                     │
         ┌───────────┼───────────┐
         ▼           ▼           ▼
    PostgreSQL     Redis      Worker
    TimescaleDB                 App
```

Docker Compose services: `nginx`, `web`, `api`, `worker`, `postgres`, `redis`

---

## Scaling Roadmap

| Stage | Change |
|-------|--------|
| 1 | Modular Monolith + single Worker |
| 2 | Split workers by domain (market / news / event / alert / AI) |
| 3 | Replace Redis Streams with Kafka |
| 4 | Split into microservices only if traffic demands it |

---

## Resilience

| Failure | Response |
|---------|----------|
| External price API down | Show last known price, display "delayed" badge, retry |
| News collector failure | Show last collected time, retry queue, alert ops |
| WebSocket disconnect | Client auto-reconnect, re-fetch snapshot, REST fallback |
| Worker crash | Job status table, retry limit, dead letter queue, monitoring alert |

---

## Security

- JWT access token + refresh token rotation
- HttpOnly cookie or Secure storage
- Rate limiting on all public APIs
- CORS restriction
- Strict input validation
- Encrypted external API keys
- Never implement real order execution without explicit authorization
