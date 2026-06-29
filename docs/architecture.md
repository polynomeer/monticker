# monticker — Architecture

> Read this when: designing a module, adding a new table, wiring up a new worker, or making any infrastructure decision.

## Core Principle

monticker is **event-centric**, not price-centric.

```
External data sources
  → collectors / workers
  → Redis (latest price / orderbook) + TimescaleDB (ticks / candles)
  → Event Detector
  → stock_events
  → REST / WebSocket API
  → chart timeline (web / mobile)
```

Quant Lab adds a second pipeline:

```
Market Data Event
  → Indicator Engine (MA, RSI, MACD, Bollinger …)
  → Rule Engine (evaluate user rulesets)
  → Signal Event
  → Portfolio / Risk Check
  → Paper Trading Order (mock auto-trade)
  → Matching Engine
  → Execution Event → Strategy Performance Update
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
Migration:   Flyway (V1–V12)
Batch:       Spring @Scheduled
Resilience:  Resilience4j (Circuit Breaker)
Observability: OpenTelemetry + Jaeger, Micrometer
```

### Frontend

```
Framework:   Next.js 15 (App Router)
Language:    TypeScript
Server state: TanStack Query
Client state: Zustand
Chart:       Lightweight Charts 4   ← use addCandlestickSeries(), NOT addSeries()
UI:          Tailwind CSS (Dracula dark theme)
Realtime:    WebSocket (STOMP)
Virtualisation: TanStack Virtual (screener, 500 rows → ~17 DOM nodes)
```

### Mobile

```
Framework:   Expo 52 (React Native)
Push:        Expo Push Notifications
```

### Database

```
Business data:    PostgreSQL 16
Time-series:      TimescaleDB (candles_1m, candles_1d)
Cache / Realtime: Redis 7
```

### Infra

```
Local:      Docker Compose
CI/CD:      GitHub Actions
Tracing:    Jaeger (all-in-one)
```

---

## Backend Module Boundaries

### Implemented Modules

| Module | Tables | Status |
|--------|--------|--------|
| Stock | `stocks`, `stock_aliases` | Done |
| Auth | `users`, `refresh_tokens` | Done |
| Watchlist | `watchlist_groups`, `watchlist_items` | Done |
| Market Data | `candles_1m`, `candles_1d_cagg` | Done |
| Event Timeline | `stock_events` | Done |
| Alert | `alert_rules`, `alert_histories` | Done |
| Paper Trading | `paper_accounts`, `paper_orders`, `paper_holdings` | Done |
| News | `news_articles`, `news_stock_mappings` | Done |
| Screener | Redis-based ranking, JDBC queries | Done |
| Order Book | KIS WebSocket → Redis / Yahoo Finance / Mock chain | Done |
| VWAP | Computed from candles_1m | Done |
| Latency Tracking | Micrometer Timer, `/api/latency` | Done |

### Planned Modules (Quant Lab)

| Module | Responsibility |
|--------|---------------|
| **Rule Builder** | Ruleset CRUD, condition JSON, version management |
| **Rule Engine** | Evaluate conditions against live indicators; emit signals |
| **Indicator Engine** | MA, EMA, RSI, MACD, Bollinger, ATR from candle data |
| **Backtest Engine** | Historical simulation, commission/slippage, reliability score |
| **Forward Test Engine** | Live-market signal logging, vs-backtest comparison |
| **Strategy Vault** | Encrypted ruleset storage, fingerprint, access control |

### Planned Modules (Investment Wallet)

| Module | Responsibility |
|--------|---------------|
| **Ledger Service** | 원장 이벤트 기록·조회. 잔고 = 이벤트 replay 합산 |
| **Wallet Service** | 현금·예약금·평가액·정산대기 상태 실시간 집계 |
| **Order State Machine** | 주문접수 → 예약 → 부분체결 → 전량체결 → 정산 상태 전이 |
| **Receipt Service** | 체결 후 영수증 생성 (체결금·수수료·정산 상태) |
| **Emotion Tag Service** | 주문 감정 태그 저장 + 수익률 연계 분석 |
| **Replay Service** | 하루 투자 이벤트 스트림 재구성 |
| **Behavior Score Service** | 투자 행동 점수 / 생존 점수 계산 |
| **Strategy Marketplace** | Listing, subscription, badge system, compliance checks |
| **Quant Analytics** | Over-optimisation detection, phase-based performance, signal attribution |

---

## Worker Pipeline (current)

```
MockPriceGenerator (@Scheduled 1s)   ← swaps to KisPriceProvider when KIS keys set
  │
  ├── RedisTickWriter
  │     └── SET stock:price:{market}:{symbol}
  │
  ├── EventDetector
  │     ├── PriceSpikeDetector  (EMA α=0.1)
  │     ├── VolumeSurgeDetector (EMA α=0.1, ≥3× ratio)
  │     └── INSERT stock_events (dedup by minute window)
  │
  └── AlertEvaluator (@Scheduled 30s)
        ├── PRICE_ABOVE / PRICE_BELOW
        ├── 10-minute cooldown (Redis key)
        └── INSERT alert_histories → Expo push
```

### KIS WebSocket (when KIS_APP_KEY + KIS_APP_SECRET set)

```
KisOrderBookSubscriber (@PostConstruct)
  └── KisWebSocketClient → ws://ops.koreainvestment.com:21000
        └── H0STASP0 (실시간 호가) → KisOrderBookHandler
              └── SET orderbook:{symbol} (TTL 30s)
```

### Order Book Provider Chain (API)

```
GET /api/stocks/{id}/orderbook
  └── OrderBookService
        ├── KisOrderBookProvider   → Redis orderbook:{symbol}   (KIS realtime)
        ├── YahooFinanceOrderBookProvider → v8/finance/chart API (15m delay, ORDERBOOK_PROVIDER=yahoo)
        └── MockOrderBookProvider  → tick-based simulation      (fallback)
```

Response includes `source: KIS_REALTIME | YAHOO_FINANCE | MOCK`.

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

## Quant Lab — Planned Architecture

### Rule Engine

```
MarketDataEvent (from Redis/WebSocket tick)
  → IndicatorEngine.compute(stockId, indicators=[MA20, RSI14, ...])
  → RuleEvaluator.evaluate(ruleSet, indicators)   ← never sends ruleset to client
  → Signal: { ruleSetId, stockId, direction, triggeredAt }
  → SignalEvent published (Redis pub/sub or in-process)
  → ForwardTestLogger.record(signal)
  → PaperAutoTrader.execute(signal)  [if strategy in auto-trade mode]
```

### Backtest Engine

```
BacktestRequest { ruleSetId, startDate, endDate, universe }
  → CandleLoader (TimescaleDB candles_1m / candles_1d)
  → IndicatorEngine (batch compute)
  → RuleEvaluator (iterate candles chronologically, no look-ahead)
  → TradeSimulator (apply commission 0.015%, tax 0.2%, slippage 0.1%)
  → MetricsCalculator:
      totalReturn, annualReturn, mdd, winRate, profitFactor,
      tradeCount, avgHoldingDays, benchmarkReturn (KOSPI/NASDAQ)
  → ReliabilityScorer (A/B/C/D):
      penalise: low trade count, high param change count,
                survivorship bias, out-of-sample gap > 20%
  → BacktestResult (saved to DB)
```

### Strategy Protection

```
Ruleset stored encrypted in DB: ruleDefinitionEncrypted
Fingerprint: ruleSetFingerprint = SHA-256(normalize(ruleDefinition))

GET /api/strategies/{id}/signal   (subscriber endpoint)
  → server evaluates encrypted ruleset
  → returns: { hasSignal: true, direction: BUY, stockCount: 3 }
  → never returns: individual condition results or indicator values
```

---

## Flyway Migrations

| Version | Description |
|---------|-------------|
| V1 | Create stocks table |
| V2 | Create users |
| V3 | Create watchlists |
| V4 | Create market data (candles) |
| V5 | Create stock_events |
| V6 | Create alerts |
| V7 | Add refresh_tokens |
| V8 | Create news_articles, news_stock_mappings |
| V9 | Create device_tokens |
| V10 | Create candle continuous aggregates |
| V11 | Create paper trading tables |
| V12 | Seed 202 stocks (KOSPI/KOSDAQ/NASDAQ/NYSE) |
| V13 | Create Quant Lab tables (rule_sets, backtest_results, quant_signals) |
| V14 | *(planned)* Create Investment Wallet tables (ledger, wallet_snapshots, emotion_tags) |

---

## API Endpoints

### REST (implemented)

```http
GET    /api/stocks/search?query=
GET    /api/stocks/{stockId}
GET    /api/stocks/{stockId}/price
GET    /api/stocks/{stockId}/candles?interval=1m&from=&to=
GET    /api/stocks/{stockId}/events?from=&to=
GET    /api/stocks/{stockId}/orderbook        ← source: KIS_REALTIME|YAHOO|MOCK
GET    /api/stocks/{stockId}/vwap

GET    /api/screener?tab=&market=&sort=&page=

GET    /api/watchlists
POST   /api/watchlists/groups
POST   /api/watchlists/{groupId}/items
DELETE /api/watchlists/items/{itemId}

GET    /api/alerts/rules
POST   /api/alerts/rules
PATCH  /api/alerts/rules/{ruleId}
DELETE /api/alerts/rules/{ruleId}

POST   /api/auth/register
POST   /api/auth/login
POST   /api/auth/refresh

GET    /api/paper/portfolio
POST   /api/paper/orders
GET    /api/paper/orders

GET    /api/backtest/run
GET    /api/latency
```

### REST (planned — Quant Lab)

```http
POST   /api/quant/rulesets                    # 룰셋 생성
GET    /api/quant/rulesets/{id}               # 내 룰셋 조회
PUT    /api/quant/rulesets/{id}               # 수정 (새 버전)
DELETE /api/quant/rulesets/{id}

POST   /api/quant/rulesets/{id}/backtest      # 백테스트 실행
GET    /api/quant/rulesets/{id}/backtest/{runId}

POST   /api/quant/rulesets/{id}/forward-test/start
GET    /api/quant/rulesets/{id}/forward-test

GET    /api/strategies/{id}/signal            # 신호 조회 (원문 비공개)
GET    /api/strategy-market                   # 전략 마켓 목록
POST   /api/strategy-market/{id}/subscribe
```

### REST (planned — Investment Wallet)

```http
GET    /api/wallet                            # 돈의 이동 지도 (현금/예약금/평가액/정산대기)
GET    /api/wallet/ledger                     # 원장 이벤트 스트림
GET    /api/wallet/timeline                   # 내 돈 이동 타임라인

GET    /api/paper/orders/{id}/receipt         # 투자 영수증
GET    /api/paper/replay?date=                # 주문 리플레이

GET    /api/wallet/score                      # 투자 행동 점수
GET    /api/wallet/survival-score             # 투자 생존 점수

GET    /api/paper/orders/{id}/emotion-tags    # 감정 태그 조회
POST   /api/paper/orders/{id}/emotion-tags    # 감정 태그 저장
GET    /api/wallet/emotion-analysis           # 감정 태그 × 수익률 분석
```

### WebSocket (STOMP)

Connect: `ws://localhost:8080/ws` (SockJS fallback)

| Topic | Description |
|-------|-------------|
| `/topic/stocks/{stockId}` | 종목별 실시간 가격 |
| `/topic/market` | 전체 시장 요약 |
| `/topic/signals/{userId}` | *(planned)* 룰셋 신호 알림 |

---

## Investment Wallet — Planned Architecture

### Order State Machine

```
PaperOrder
  status: PENDING → RESERVED → PARTIALLY_FILLED → FILLED → SETTLED

상태 전이 시 LedgerEvent 생성:
  PENDING      → ORDER_PLACED      (잔고 변경 없음)
  RESERVED     → CASH_RESERVED     (available_cash -= amount)
  PARTIALLY_FILLED → PARTIAL_FILL  (reserved -= filled_amount, holdings += qty)
  FILLED       → FULL_FILL         (reserved = 0)
  SETTLED      → SETTLEMENT        (정산 완료, 최종 원장 확정)
```

### Ledger (원장) Pattern

```
잔고 계산 원칙: 잔고 = 모든 LedgerEvent를 시간순으로 replay한 합산
잔고를 별도 컬럼으로 관리하지 않음 → 이벤트 소싱 패턴

LedgerEvent types:
  DEPOSIT          +현금
  WITHDRAWAL       -현금
  CASH_RESERVED    -available_cash, +reserved_cash
  CASH_UNRESERVED  +available_cash, -reserved_cash
  FILL             +holdings, -reserved_cash (체결가 차이는 수수료로)
  FEE              -현금 (수수료)
  SETTLEMENT       정산 완료 (settlement_pending → settled)
```

### Behavior Score Calculation

```
투자 행동 점수 (0~100):
  +20  분할 매수 비율 > 50%
  +15  손절가 설정 후 지킴
  +15  급등 후 3분 이내 추격매수 없음
  -20  급등 직후 5분 이내 매수 비율 > 30%
  -15  동일 종목 당일 3회 이상 매수

투자 생존 점수 (0~100):
  -30  단일 종목 비중 > 70%
  -20  현금 비중 < 5%
  -15  1시간 내 주문 횟수 > 5
  -10  급등주 비중 > 40%
```

---

## Redis Key Schema

```
stock:price:{market}:{symbol}      # latest price JSON (STRING)
orderbook:{symbol}                 # KIS realtime orderbook (STRING, TTL 30s)
alert:cooldown:{ruleId}            # cooldown flag (STRING, TTL 600s)
signal:forward:{ruleSetId}:{date}  # (planned) forward test daily signal log
wallet:snapshot:{userId}           # (planned) 최신 wallet 스냅샷 캐시 (TTL 30s)
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KIS_APP_KEY` | — | KIS WebSocket 실시간 호가 활성화 |
| `KIS_APP_SECRET` | — | KIS 인증 |
| `ORDERBOOK_PROVIDER` | mock | `yahoo` = Yahoo Finance 15분 지연 |
| `ANTHROPIC_API_KEY` | — | AI 뉴스 요약 |
| `NAVER_CLIENT_ID` | — | 네이버 뉴스 수집 |
| `DART_API_KEY` | — | 공시 수집 |

---

## Scaling Roadmap

| Stage | Change |
|-------|--------|
| 1 | Modular Monolith + single Worker (current) |
| 2 | Split workers by domain (market / event / alert / rule-engine) |
| 3 | Rule Engine as dedicated service (CPU-intensive backtest isolation) |
| 4 | Replace in-process pub/sub with Kafka for signal fanout |
| 5 | Microservices only if traffic demands |

---

## Security

- JWT (Access 15min + Refresh 7d), token rotation on refresh
- BCryptPasswordEncoder
- CORS restricted to `localhost:3000` / `*.monticker.io`
- Rate limiting on public APIs
- Quant ruleset: never serialised to client — server-side evaluation only
- Ruleset fingerprint (SHA-256) for tamper detection
- Signal query rate-limited (reverse-engineering prevention)
