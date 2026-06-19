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
┌─────────────┐   WebSocket/REST   ┌─────────────────┐
│  Next.js 15 │ ◄────────────────► │  Spring Boot API │
│  (apps/web) │                    │  (backend/api)   │
└─────────────┘                    └────────┬─────────┘
                                            │ JPA / JDBC
┌─────────────┐   Expo Push        ┌────────▼─────────┐
│  Expo Mobile│ ◄── notification ─ │   TimescaleDB     │
│(apps/mobile)│                    │  (PostgreSQL 16)  │
└─────────────┘                    └────────▲─────────┘
                                            │ JDBC
                                   ┌────────┴─────────┐
                                   │  Spring Worker   │
                                   │ (backend/worker) │
                                   └──────────────────┘
```

---

## Tech Stack

### Backend

```
Language:    Kotlin 2.0
Framework:   Spring Boot 3.5
Pattern:     Modular Monolith
API:         REST + WebSocket (STOMP over SockJS)
ORM:         Spring Data JPA
Migration:   Flyway (V1–V6)
Batch:       Spring @Scheduled
```

### Frontend

```
Framework:   Next.js 15 (App Router)
Language:    TypeScript
Server state: TanStack Query
Client state: Zustand
Chart:       Lightweight Charts 4
UI:          Tailwind CSS + shadcn/ui
Realtime:    WebSocket (STOMP)
```

### Mobile

```
Framework:   Expo 52 (React Native)
Push:        Expo Push Notifications
```

### Database

```
Business data:    PostgreSQL 16
Time-series:      TimescaleDB (candles_1m)
Cache / Realtime: Redis 7
```

### Infra

```
Local:      Docker Compose
CI/CD:      GitHub Actions
```

---

## Backend Module Boundaries

### Implemented Modules (MVP)

| Module | Tables | Status |
|--------|--------|--------|
| Stock | `stocks` | Done |
| Watchlist | `watchlist_groups`, `watchlist_items` | Done |
| Market Data | `candles_1m` | Done |
| Event Timeline | `stock_events` | Done |
| Alert | `alert_rules`, `alert_histories` | Done |

### Stock Module

Manages static stock metadata.

Tables: `stocks`

### Market Data Module

Collects and caches price ticks and candles.

Tables: `candles_1m` (TimescaleDB hypertable)

```sql
CREATE TABLE candles_1m (
    stock_id    BIGINT         NOT NULL,
    candle_time TIMESTAMPTZ    NOT NULL,
    open        NUMERIC(18,4),
    high        NUMERIC(18,4),
    low         NUMERIC(18,4),
    close       NUMERIC(18,4),
    volume      BIGINT,
    PRIMARY KEY (stock_id, candle_time)
);
SELECT create_hypertable('candles_1m', 'candle_time');
```

### Event Timeline Module

**The most important module.** Centralizes all event types into a single queryable table for the chart timeline.

Tables: `stock_events`

Event types:
```
PRICE_SPIKE / PRICE_DROP
VOLUME_SURGE
```

Duplicate prevention via unique index:
```sql
CREATE UNIQUE INDEX ON stock_events (stock_id, event_type, date_trunc('minute', event_time));
```

### Watchlist Module

Tables: `watchlist_groups`, `watchlist_items`

### Alert Module

Tables: `alert_rules`, `alert_histories`

Supported rule types: `PRICE_ABOVE`, `PRICE_BELOW`, `VOLUME_SURGE`

10-minute cooldown: `alert:cooldown:{ruleId}` in Redis; also enforced via DB query on `alert_histories`.

---

## Worker Pipeline

```
MockPriceGenerator (@Scheduled 1s)
  │
  ├── RedisTickWriter
  │     └── SET stock:price:{stockId} → latest price JSON
  │
  ├── EventDetector
  │     ├── PriceSpikeDetector  (EMA α=0.1, ratio threshold)
  │     ├── VolumeSurgeDetector (EMA α=0.1, ≥3× ratio)
  │     └── INSERT stock_events (dedup by minute index)
  │
  └── AlertEvaluator (@Scheduled 30s)
        ├── Fetch active alert_rules
        ├── Evaluate PRICE_ABOVE / PRICE_BELOW against candles_1m
        ├── 10-minute cooldown check
        └── INSERT alert_histories
```

### EMA-based Anomaly Detection

```
EMA(t) = α × value(t) + (1 - α) × EMA(t-1)   where α = 0.1

PriceSpikeDetector:
  changeRate = abs(current - ema) / ema
  fire PRICE_SPIKE if changeRate ≥ spikeThreshold

VolumeSurgeDetector:
  ratio = currentVolume / emaVolume
  fire VOLUME_SURGE if ratio ≥ 3.0
```

---

## Flyway Migrations

| Version | Description |
|---------|-------------|
| V1 | Create stocks table |
| V2 | Create watchlist_groups, watchlist_items |
| V3 | Create candles_1m (TimescaleDB hypertable) |
| V4 | Create stock_events with dedup index |
| V5 | Create alert_rules, alert_histories |
| V6 | Seed sample stocks (Samsung, SK Hynix, NAVER, Kakao, Hyundai) |

---

## API Endpoints

### REST

```http
GET    /api/stocks/search?query=
GET    /api/stocks/{stockId}
GET    /api/stocks/{stockId}/price
GET    /api/stocks/{stockId}/candles?interval=1m&from=&to=
GET    /api/stocks/{stockId}/events?from=&to=

GET    /api/watchlists
POST   /api/watchlists/groups
POST   /api/watchlists/{groupId}/items
DELETE /api/watchlists/items/{itemId}

GET    /api/alerts/rules
POST   /api/alerts/rules
PATCH  /api/alerts/rules/{ruleId}
DELETE /api/alerts/rules/{ruleId}
GET    /api/alerts/histories
```

### WebSocket (STOMP)

Connect: `ws://localhost:8080/ws` (SockJS fallback)

| Topic | Description |
|-------|-------------|
| `/topic/stocks/{stockId}` | 종목별 실시간 가격 |
| `/topic/market` | 전체 시장 요약 |

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

## Redis Key Schema

```
stock:price:{stockId}          # latest price JSON (STRING)
alert:cooldown:{ruleId}        # cooldown flag (STRING, TTL 600s)
```

---

## Mobile Push Notification Flow

```
AlertEvaluator (Worker)
  → INSERT alert_histories (delivery_status = PENDING)
  → [future] AlertDispatcher reads PENDING rows
  → Expo Push API → device FCM/APNs token
  → delivery_status = DELIVERED / FAILED
```

Current MVP: alert is recorded in DB. Expo push dispatch is stubbed for EAS integration.

---

## Realtime Data Pipeline

```
MockPriceGenerator (1s)
  ├── Redis latest price cache
  └── EventDetector → stock_events INSERT

API WebSocket Broadcaster
  └── @Scheduled 1s → reads Redis price → broadcast /topic/stocks/{id}
```

---

## Frontend Structure

```
apps/web/
├── app/
│   ├── page.tsx                 # Home — market overview
│   ├── stocks/[symbol]/page.tsx # Stock detail + chart + events
│   ├── watchlist/               # Watchlist groups
│   └── alerts/                  # Alert rules management
├── components/
│   ├── chart/
│   │   ├── StockChart.tsx       # Lightweight Charts wrapper
│   │   ├── EventMarker.tsx      # Event overlay markers
│   │   └── TimelinePanel.tsx    # Event timeline list
│   └── common/
├── hooks/
│   ├── useStockPrice.ts
│   ├── useStockEvents.ts
│   ├── useWatchlist.ts
│   └── useWebSocket.ts
└── stores/
    └── realtimeStore.ts
```

State management:
- Server state → TanStack Query
- Realtime state → Zustand
- Chart state → component-local

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
| 1 | Modular Monolith + single Worker (current) |
| 2 | Split workers by domain (market / event / alert) |
| 3 | Replace Redis Streams with Kafka |
| 4 | Split into microservices only if traffic demands it |

---

## Security

- Rate limiting on public APIs
- CORS restriction
- Strict input validation
- Encrypted external API keys
- JWT auth planned (post-MVP)
