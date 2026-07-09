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

Start as **Modular Monolith + async workers + Redis + TimescaleDB**. Microservices are enabled via `docker compose --profile msa up` (see [MSA Architecture](#msa-architecture) below).

---

## System Overview

### Monolith mode (`make up-full`)

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
                              ┌─────────────┴────────────────┐
                              │  Spring Worker (role=all)    │
                              │  MockPriceGenerator → Kafka  │
                              │  → TickKafkaConsumer         │
                              └──────────────────────────────┘
```

### MSA mode (`make up-msa`)

```
[Next.js :3000]  [Expo Mobile]
        │  REST / WebSocket
        ▼
┌─────────────────────────────────────┐
│       backend/api  :8080            │
│  JWT Auth · Strangler-fig proxy     │
│  QUANT_ENGINE_URL → quant-engine    │
│  TRADING_SERVICE_URL → trading-svc  │
└──────┬──────────────────┬───────────┘
       │ HTTP proxy        │ HTTP proxy
       ▼                   ▼
┌─────────────┐   ┌────────────────────┐
│quant-engine │   │  trading-service   │
│  :8082      │   │  :8083             │
│ analytics   │   │  paper · matching  │
│ quant       │   │  wallet · batch    │
│ backtest    │   │  @AFTER_COMMIT →   │
└──────▲──────┘   └────────┬───────────┘
       │CONSUME             │PRODUCE
       ╔══════════════════════════════════════════════════════╗
       ║       Apache Kafka  :9092 / :29092                   ║
       ║  market.ticks │ tick-processed │ order-filled │ ...  ║
       ╚═╦═══════╦════╦════════════════════════════╦══════════╝
         │       │    │                            │
       PUB     SUB  PUB SUB                      SUB
         │       │    │    │                       │
  ┌──────┴──┐ ┌──▼────┴──┐ ┌──▼──────┐ ┌────────────────┐ ┌──────────────────┐
  │worker-  │ │worker-   │ │worker- │ │market-gateway │ │broadcast-gateway │
  │market   │ │event     │ │alert   │ │(Go)           │ │(Netty :9090)     │
  └─────────┘ └──────────┘ └────────┘ └───────────────┘ └──────────────────┘
                 │ JDBC + Redis (shared)
        ┌────────┴──────────────────┐
        │  TimescaleDB :5432        │   Redis :6379
        └───────────────────────────┘
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

### Realtime Pipeline (optional — `kafka` profile)

```
Ingestion:  Go (goroutine-per-stock tick generator/gateway)
Bus:        Kafka (KRaft mode, single broker)
Broadcast:  Netty (WebSocket server, bypasses Spring STOMP)
```

See [ADR-005](decisions/005-kafka-go-gateway-netty-broadcast.md) and [kafka-tick-pipeline.md](technical/kafka-tick-pipeline.md). Disabled by default — the in-process `MockPriceGenerator` path remains the default for local dev.

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
| Paper Trading | `paper_accounts`, `paper_trades` | Done (simple instant-fill) |
| Matching Engine | `orders`, `fills` | Done (CLOB, price/time priority) |
| Risk Limit System | `risk_limits`, `risk_check_logs` | Done (5 pre-trade rules) |
| News | `news_articles`, `news_stock_mappings` | Done |
| Screener | Redis-based ranking, JDBC queries | Done |
| Order Book | KIS WebSocket → Redis / Yahoo Finance / Mock chain | Done |
| VWAP | Computed from candles_1m | Done |
| Latency Tracking | Micrometer Timer, `/api/latency` | Done |

### Implemented Modules (Quant Lab — V13)

| Module | Tables | Status |
|--------|--------|--------|
| **Rule Builder** | `rule_sets` | Done — Ruleset CRUD, condition JSON, version management |
| **Rule Engine** | `quant_signals` | Done — Evaluate conditions against live indicators; emit signals |
| **Indicator Engine** | (in-memory) | Done — MA, EMA, RSI, MACD, Bollinger, ATR from candle data |
| **Backtest Engine** | `backtest_results` | Done — Historical simulation, commission/slippage, reliability score |
| **Forward Test Engine** | `quant_signals` | Done — Live-market signal logging, vs-backtest comparison |
| **Strategy Vault** | `rule_sets.rule_set_fingerprint` | Done — SHA-256 fingerprint, server-side evaluation only |

### Implemented Modules (Quant Analytics — V16)

| Module | Tables | Status |
|--------|--------|--------|
| **Portfolio Optimizer** | (in-memory) | Done — Markowitz, projected gradient descent, efficient frontier |
| **Tax Optimizer** | `harvesting_logs` | Done — 손익통산 시뮬레이션, 절세 후보 추출 |
| **Position Sizer** | (in-memory) | Done — Kelly Criterion, Half Kelly 권장 비율 |
| **Pattern Recognizer** | `detected_patterns` | Done — ZigZag + 5개 차트 패턴, 완성도 점수 |
| **Regime Detector** | `regime_history` | Done — ADX 기반 BULL/BEAR/SIDEWAYS/HIGH_VOL 분류 |

### Implemented Modules (Investment Wallet — V14)

| Module | Tables | Status |
|--------|--------|--------|
| **Ledger Service** | `ledger_events` | Done — 이벤트 소싱, 잔고 = 이벤트 replay 합산 |
| **Wallet Service** | (ledger_events 집계) | Done — 현금·예약금·평가액·정산대기 상태 집계 |
| **Receipt Service** | (paper_orders 기반) | Done — 체결 후 영수증 생성 (체결금·수수료·정산 상태) |
| **Emotion Tag Service** | `order_emotion_tags` | Done — 주문 감정 태그 저장 + 수익률 연계 분석 |
| **Replay Service** | (ledger_events 스트림) | Done — 하루 투자 이벤트 스트림 재구성 |
| **Behavior Score Service** | `investment_behavior_scores` | Done — 투자 행동 점수 / 생존 점수 계산 |

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

### Kafka Ingestion Path (when `ingestion.source=kafka`, `kafka` profile)

```
Go Market Gateway (goroutine per stock, 1s tick loop)
  └── produce → Kafka topic: market.ticks (key=stockId)
        ├── TickKafkaConsumer (Worker, @KafkaListener)
        │     └── RedisTickWriter / CandleAggregator / EventDetector
        │           └── produce → Kafka topic: market.events (on detection)
        └── Netty Broadcast Gateway (Kafka consumer)
              └── WebSocket clients (ws://localhost:9090/ws)
```

Replaces the `MockPriceGenerator` polling loop with a push-based Kafka consumer. See [kafka-tick-pipeline.md](technical/kafka-tick-pipeline.md).

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

## Quant Lab — Architecture

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
| V14 | Create Investment Wallet tables (ledger_events, order_emotion_tags, investment_behavior_scores) |
| V15 | Create Matching Engine tables (orders, fills, risk_limits, risk_check_logs) |
| V16 | Create Quant Analytics tables (detected_patterns, regime_history, harvesting_logs) |

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

# Quant Lab
POST   /api/quant/rulesets                    # 룰셋 생성
GET    /api/quant/rulesets/{id}               # 내 룰셋 조회
PUT    /api/quant/rulesets/{id}               # 수정 (새 버전)
DELETE /api/quant/rulesets/{id}
POST   /api/quant/rulesets/{id}/backtest      # 백테스트 실행
GET    /api/quant/rulesets/{id}/backtest/{runId}
POST   /api/quant/rulesets/{id}/forward-test/start
GET    /api/quant/rulesets/{id}/forward-test

# Quant Analytics
GET    /api/analytics/portfolio/optimize?targetReturn=    # 효율적 프론티어 + 추천 비중
GET    /api/analytics/portfolio/frontier                  # 효율적 프론티어 전체 곡선
GET    /api/analytics/tax/harvesting-candidates           # 손익통산 후보
POST   /api/analytics/tax/simulate                        # 손실 매도 시뮬레이션
GET    /api/analytics/position-size/kelly?ruleSetId=      # 켈리 비율 계산
GET    /api/stocks/{id}/patterns                          # 감지된 차트 패턴
GET    /api/stocks/{id}/regime                            # 현재 시장 국면

# Matching Engine + Risk
POST   /api/matching/orders                   # 주문 접수 (리스크 체크 → 체결 엔진)
DELETE /api/matching/orders/{id}              # 주문 취소
GET    /api/matching/orders                   # 내 미체결 주문
GET    /api/matching/orders/{id}/fills        # 체결 내역
GET    /api/risk/limits                       # 내 리스크 한도 조회
PUT    /api/risk/limits                       # 한도 설정
POST   /api/risk/check                        # 주문 전 리스크 시뮬레이션 (dry-run)
GET    /api/risk/exposure                     # 현재 포트폴리오 리스크 노출도

# Investment Wallet
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
| `/topic/signals/{userId}` | 룰셋 신호 알림 (Quant Lab) |

---

## Matching Engine — Architecture

### 설계 원칙

실제 거래소의 Central Limit Order Book(CLOB) 구조를 모사한다.  
모의투자이지만 체결 로직은 실거래소 규칙을 따른다.

```
주문 접수 (POST /api/matching/orders)
  │
  ├─► RiskChecker.preCheck()          ← 리스크 한도 초과 시 즉시 REJECTED
  │
  ├─► OrderBook.submit(order)
  │     ├── MARKET order → 즉시 최우선 반대호가와 매칭
  │     └── LIMIT order  → 호가 조건 미충족 시 Order Book에 등록 대기
  │
  ├─► MatchingEngine.match()
  │     ├── 가격 우선: 매수는 높은 가격, 매도는 낮은 가격부터
  │     └── 시간 우선: 동일 가격 내 먼저 접수된 주문 우선
  │
  ├─► FillEvent 발생
  │     ├── LedgerService.recordFill()    ← 원장 기록
  │     └── WebSocket broadcast          ← 실시간 체결 알림
  │
  └─► OrderStatus 전이
        PENDING → PARTIALLY_FILLED → FILLED | CANCELLED
```

### Order Book 자료구조

```kotlin
// 매도호가: 낮은 가격 우선 (TreeMap ascending)
// 매수호가: 높은 가격 우선 (TreeMap descending)
class OrderBook(val stockId: Long) {
    val asks: TreeMap<BigDecimal, ArrayDeque<Order>> = TreeMap()          // price → FIFO queue
    val bids: TreeMap<BigDecimal, ArrayDeque<Order>> = TreeMap(reverseOrder())
}
```

### 체결 우선순위

```
1. 가격 우선 (Price Priority)
   매수: 더 높은 가격을 제시한 주문이 먼저 체결
   매도: 더 낮은 가격을 제시한 주문이 먼저 체결

2. 시간 우선 (Time Priority)
   동일 가격 내에서는 먼저 접수된 주문이 먼저 체결

3. MARKET 주문은 항상 LIMIT 주문보다 우선
```

### 슬리피지 시뮬레이션

대량 주문은 여러 호가 레벨에 걸쳐 체결되어 평균 체결가가 불리해진다.

```
주문 수량 > 최우선 호가 잔량 → 다음 레벨로 넘어가며 체결
체결가 = 각 레벨 가격 × 해당 레벨 체결 수량 의 가중평균

예시:
  매수 주문: 1000주 @ MARKET
  매도 호가: 50,000원 × 300주, 50,100원 × 400주, 50,200원 × 500주
  체결:      300주@50,000 + 400주@50,100 + 300주@50,200
  평균 체결가: 50,090원  (단순 50,000원보다 불리)
```

---

## Risk Limit System — Architecture

### 설계 원칙

주문이 체결되기 **전**에 동기적으로 실행되는 리스크 게이트.  
한도 초과 주문은 `REJECTED` 상태로 즉시 반환되며 체결 엔진에 도달하지 않는다.

```
RiskChecker.preCheck(userId, order):
  1. DailyLossLimitRule    → 오늘 실현 손실이 한도(기본 3%) 초과 여부
  2. ConcentrationRule     → 주문 후 특정 종목 비중이 한도(기본 30%) 초과 여부
  3. VaRLimitRule          → 95% VaR가 총 자산의 한도(기본 5%) 초과 여부
  4. PositionCountRule     → 보유 종목 수가 한도(기본 10개) 초과 여부
  5. TradingFrequencyRule  → 1시간 내 주문 횟수가 한도(기본 5회) 초과 여부

모든 규칙 통과 → RiskCheckResult.APPROVED
하나라도 실패 → RiskCheckResult.REJECTED(reason, severity)
```

### RiskCheckResult

```kotlin
data class RiskCheckResult(
    val approved: Boolean,
    val checks: List<RuleResult>,   // 각 규칙별 통과/실패 + 상세 수치
    val blockedBy: String?,         // 거부 이유 (사용자에게 표시)
    val severity: Severity,         // INFO | WARNING | BLOCKED
)

// 예시 응답
{
  "approved": false,
  "blockedBy": "일일 손실 한도 초과",
  "severity": "BLOCKED",
  "checks": [
    { "rule": "DAILY_LOSS", "passed": false,
      "detail": "오늘 손실 -3.4% / 한도 -3.0%", "current": -3.4, "limit": -3.0 },
    { "rule": "CONCENTRATION", "passed": true,
      "detail": "삼성전자 비중 22.1% / 한도 30%", "current": 22.1, "limit": 30.0 }
  ]
}
```

### Dry-run API

주문 실행 없이 리스크 체크 결과만 반환.  
프론트엔드에서 "주문 전 리스크 확인" 버튼으로 호출.

```http
POST /api/risk/check
{ "stockId": 1, "side": "BUY", "quantity": 100, "orderType": "MARKET" }

→ RiskCheckResult (체결 없음)
```

---

## Portfolio Optimizer — Architecture

### 설계 원칙

Markowitz 평균-분산 최적화. 보유/관심 종목군의 과거 수익률 공분산 행렬을 계산하고,
목표 수익률 대비 분산을 최소화하는 비중을 수치 최적화로 구한다.

```
PortfolioOptimizer.optimize(stockIds, targetReturn):
  1. 각 종목의 일별 수익률 시계열 추출 (candles_1d, 최근 1년)
  2. 평균 수익률 벡터(μ), 공분산 행렬(Σ) 계산
  3. 이차계획법(QP)으로 최소분산 비중 탐색:
       minimize   wᵀΣw
       subject to wᵀμ = targetReturn, Σw = 1, w ≥ 0 (공매도 불가)
  4. 효율적 프론티어: targetReturn을 스윕하며 (위험, 수익) 곡선 생성
```

### 수치 최적화 구현

순수 QP 솔버 라이브러리 없이 **프로젝션 경사하강법(Projected Gradient Descent)** 으로 근사:

```kotlin
fun minimizeVariance(cov: Matrix, mu: Vector, targetReturn: Double): Vector {
    var w = uniformWeights(n)               // 초기값: 균등 비중
    repeat(maxIterations) {
        val gradient = cov.times(w).times(2.0)
        w = w.minus(gradient.times(learningRate))
        w = projectToSimplex(w)              // Σw=1, w≥0 제약 투영
        w = adjustForTargetReturn(w, mu, targetReturn)
    }
    return w
}
```

### 효율적 프론티어 응답

```json
{
  "frontier": [
    { "expectedReturn": 0.04, "risk": 0.08, "weights": {"005930": 0.6, "AAPL": 0.4} },
    { "expectedReturn": 0.08, "risk": 0.15, "weights": {"005930": 0.3, "AAPL": 0.7} }
  ],
  "currentPortfolio": { "expectedReturn": 0.05, "risk": 0.12 },
  "suggestion": "현재 포트폴리오는 프론티어 아래에 있습니다. 비중 조정 시 동일 위험에서 +1.2%p 추가 수익 가능"
}
```

---

## Tax Optimizer — Architecture

### 설계 원칙

한국 주식 양도소득세·증권거래세 규칙을 단순화해 손익통산 시뮬레이션을 제공한다.
**모의투자 전용 교육 기능**이며 실제 세무 신고에 사용할 수 없다는 고지를 항상 포함한다.

```
TaxOptimizer.findHarvestingCandidates(userId):
  1. 현재 보유 종목 중 평가손실 종목 추출 (currentPrice < avgPrice)
  2. 올해 실현된 손익 합계 조회 (paper_trades 기준)
  3. 손실 종목 매도 시뮬레이션 → 손익통산 후 절세액 계산
       절세액 = min(실현손실, 실현이익) × 세율(22%)
  4. 후보 정렬: 절세 효과 높은 순
```

### 손익통산 시뮬레이션

```
보유 종목:
  삼성전자  평가손실 -500,000원
  NVDA      평가손실 -200,000원

올해 실현이익: +1,200,000원 (이미 양도세 22% = 264,000원 부과 가정)

손실 매도 시뮬레이션:
  삼성전자 매도 → 손실 -500,000원 실현
  → 통산 후 과세표준: 1,200,000 - 500,000 = 700,000원
  → 절세액: (1,200,000 - 700,000) × 22% = 110,000원
```

---

## Position Sizer — Architecture

### Kelly Criterion

백테스트 결과(승률, 평균 손익비)에서 파산 위험 없는 수학적 최적 베팅 비율을 계산한다.

```
f* = (bp - q) / b

f* : 자본 대비 베팅 비율
b  : 손익비 (평균 이익 / 평균 손실)
p  : 승률
q  : 패율 (1 - p)
```

```kotlin
fun kellyFraction(winRate: Double, avgWin: Double, avgLoss: Double): Double {
    val b = avgWin / avgLoss
    val p = winRate
    val q = 1 - p
    val f = (b * p - q) / b
    return f.coerceIn(0.0, 1.0)   // 음수면 베팅하지 않음
}

// 실무적으로 Full Kelly는 변동성이 매우 크므로 Half Kelly(f*/2) 권장
fun recommendedFraction(kelly: Double): Double = kelly * 0.5
```

전략 백테스트 결과 화면에 자동으로 표시:
```
이 전략의 켈리 비율: 18.4%
권장 비율 (Half Kelly): 9.2%
→ 1회 매수 시 총 자본의 9.2%를 추천합니다 (현재 설정: 10%)
```

---

## Pattern Recognizer — Architecture

### 감지 대상 패턴

```
HEAD_AND_SHOULDERS   헤드앤숄더 (하락 반전)
DOUBLE_BOTTOM        이중 바닥 (상승 반전)
DOUBLE_TOP           이중 천장 (하락 반전)
ASCENDING_TRIANGLE   상승 삼각수렴 (상승 지속)
DESCENDING_TRIANGLE  하락 삼각수렴 (하락 지속)
```

### 알고리즘 — Local Extrema 기반

```
PatternRecognizer.detect(candles):
  1. ZigZag 알고리즘으로 국소 고점/저점(swing points) 추출
     (변동폭이 임계치 이상인 전환점만 채택, 노이즈 제거)
  2. 최근 N개 swing point 시퀀스를 패턴 템플릿과 비교
       이중바닥: [저점A, 고점, 저점B] where 저점A ≈ 저점B (±2%), 고점 > 저점×1.05
       헤드앤숄더: [어깨1, 머리, 어깨2] where 머리 > 어깨1,2 and 어깨1≈어깨2
  3. 패턴 완성도 점수(0~100) 계산 → 임계치(70) 이상만 신호 발생
  4. stock_events에 PATTERN_DETECTED 이벤트로 기록
```

```kotlin
data class SwingPoint(val index: Int, val price: BigDecimal, val type: SwingType) // HIGH | LOW

fun zigZag(candles: List<DailyCandle>, thresholdPct: Double): List<SwingPoint>

fun detectDoubleBottom(swings: List<SwingPoint>): PatternMatch? {
    // 마지막 5개 swing에서 [LOW, HIGH, LOW] 시퀀스 탐색
    // 두 저점 가격 차이 ≤ 2%, 중간 고점이 저점 대비 5% 이상 → 매치
}
```

---

## Regime Detector — Architecture

### 시장 국면 분류

```
BULL        상승장 — 추세 강도 높음, 변동성 보통
BEAR        하락장 — 하락 추세, 변동성 높음
SIDEWAYS    횡보장 — 추세 강도 낮음, 변동성 낮음
HIGH_VOL    고변동성 — 추세 무관, 변동성 매우 높음
```

### 분류 알고리즘

```
RegimeDetector.classify(candles, window=60):
  1. 추세 강도: ADX(Average Directional Index) 14일
  2. 변동성: 20일 연환산 표준편차
  3. 방향: 60일 선형회귀 기울기 부호

  분류 규칙:
    ADX < 20                        → SIDEWAYS
    변동성 > 과거 1년 80th 백분위    → HIGH_VOL
    기울기 > 0 and ADX ≥ 20         → BULL
    기울기 < 0 and ADX ≥ 20         → BEAR
```

### 백테스트 결과 연동

```
시장 국면별 성과 분해 (기존 backtest_results.phase_performance 필드 활용):

  BULL 구간     (2023.01~2023.07): 수익률 +18.2%, MDD -4.1%
  BEAR 구간     (2022.01~2022.10): 수익률  -8.4%, MDD -22.3%
  SIDEWAYS 구간 (2024.03~2024.09): 수익률  +1.1%, MDD -6.7%

  경고: 이 전략은 하락장에서 MDD가 5배 이상 확대됩니다.
```

---

## Investment Wallet — Architecture

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
signal:forward:{ruleSetId}:{date}  # daily forward test signal set
wallet:snapshot:{userId}           # 최신 wallet 스냅샷 캐시 (TTL 30s)
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

| Stage | Change | Status |
|-------|--------|--------|
| 1 | Modular Monolith + single Worker | ✅ baseline |
| 2 | Split Worker by role (`WORKER_ROLE=market/event/alert`) | ✅ implemented |
| 3 | Extract `quant-engine` as standalone service (:8082) | ✅ implemented |
| 4 | Kafka always-on — all ticks route through `market.ticks` | ✅ implemented |
| 5 | Extract `trading-service` (:8083), distributed tx via `@AFTER_COMMIT` | ✅ implemented |

---

## MSA Architecture

Full diagram: see [MSA Architecture Diagram (Artifact)](https://claude.ai/code/artifact/afddd890-919c-46cb-8d4a-37547d5e18fc)

### Deployment

```bash
# MSA 전체 기동 (Kafka + quant-engine + trading-service + 3 workers)
make up-msa

# 단일 프로세스 모드 (Kafka + api + worker(role=all))
make up-full
```

### Service Ports

| Service | Port | Docker Profile | Role |
|---------|------|---------------|------|
| `backend/api` | 8080 | `full` / `msa` | API gateway, JWT auth, strangler-fig proxy |
| `quant-engine` | 8082 | `msa` | analytics, quant, backtest |
| `trading-service` | 8083 | `msa` | paper trading, matching, wallet |
| `broadcast-gateway` | 9090 | `kafka` | Netty WebSocket fan-out |
| `kafka` | 9092 / 29092 | `full` / `kafka` / `msa` | event bus |
| `postgres` (TimescaleDB) | 5432 | always | shared DB |
| `redis` | 6379 | always | tick cache, candle, orderbook |
| `grafana` | 3001 | — | observability dashboard |
| `jaeger` | 16686 | — | distributed tracing |

### Kafka Topics

| Topic | Producer | Consumer |
|-------|----------|----------|
| `market.ticks` | `worker-market`, `market-gateway` (Go) | `worker-event`, `broadcast-gateway` |
| `market.tick-processed` | `worker-event` | `worker-alert` |
| `market.events` | `worker-event` | — |
| `trading.order-filled` | `trading-service` (`@AFTER_COMMIT`) | `quant-engine` (`QUANT_TRADING_EVENTS_ENABLED=true`) |
| `trading.order-cancelled` | `trading-service` (`@AFTER_COMMIT`) | — |

### MSA Key Design Decisions

- **Strangler-fig proxy**: `QuantEngineClient` / `TradingServiceClient` in `backend/api` return `null` when URL env vars are unset → local service handles the request. Set `QUANT_ENGINE_URL` / `TRADING_SERVICE_URL` to route to standalone services.
- **Distributed transaction safety**: `OrderEventKafkaPublisher` in `trading-service` uses `@TransactionalEventListener(AFTER_COMMIT)` — Kafka publish only fires after the matching transaction commits, preventing phantom order events on rollback.
- **Worker role activation**: `@ConditionalOnExpression("'${worker.role:all}'.matches('market|all')")` activates components per role. The `all` default keeps the monolith worker behaviour.
- **Tick ingestion dual-path**: `ingestion.source=internal` (default) → `MockPriceGenerator` → Kafka. `ingestion.source=kafka` → Go `market-gateway` → Kafka. Both paths converge at `market.ticks`.

---

## Circuit Breaker

Resilience4j Circuit Breaker를 외부 HTTP 호출 지점마다 적용한다.
OPEN 상태에서는 즉시 `null`을 반환해 **로컬 폴백** 경로로 전환되므로 타임아웃 누적으로 인한 쓰레드 풀 고갈을 막는다.

### Worker — `CircuitBreakerConfiguration` (worker 모듈)

| CB 이름 | 대상 | 실패율 임계 | 창 | OPEN 대기 | 폴백 |
|---------|------|-----------|-----|---------|------|
| `kisApi` | `KisClient` (KIS 실시세) | 50% | 10회 | 30초 | `MockPriceGenerator` |
| `expoPush` | `ExpoPushSender` (Expo Push) | 60% | 5회 | 60초 | 빈 결과 반환 |
| `naverNews` | `NaverNewsClient` (뉴스 API) | 50% | 4회 | 5분 | `MockNewsGenerator` |
| `dartApi` | `DartClient` (DART 공시 API) | 50% | 4회 | **10분** | 빈 리스트 반환 |

### API — `CircuitBreakerConfiguration` (api 모듈)

api 모듈에 `resilience4j-circuitbreaker:2.2.0` 의존성을 추가하고 별도 Bean을 등록했다.

| CB 이름 | 대상 | 실패율 임계 | 창 | OPEN 대기 | 폴백 |
|---------|------|-----------|-----|---------|------|
| `tradingService` | `TradingServiceClient` (MSA 프록시) | 50% | 6회 | 20초 | `null` → 로컬 `MatchingService` 직접 호출 |
| `quantEngine` | `QuantEngineClient` (MSA 프록시) | 50% | 4회 | 30초 | `null` → 로컬 quant 서비스 직접 호출 |
| `yahooFinance` | `YahooFinanceOrderBookProvider`, `YahooCandleReader` | 60% | 5회 | 2분 | 호가: `null`, 캔들: `MockCandleGenerator` |

> **MSA 프록시 CB 설계 의도**: `TradingServiceClient`/`QuantEngineClient`는 strangler-fig 패턴으로 URL이 설정되지 않으면 로컬 서비스로 폴백한다. CB가 OPEN이 되면 URL이 설정되어 있어도 같은 경로로 빠지므로 api 쓰레드 풀이 보호된다.

---

## Error Handling

### GlobalExceptionHandler (`api/common/exception/GlobalExceptionHandler.kt`)

`@RestControllerAdvice`로 전체 API 모듈의 예외를 일관된 JSON 형식으로 변환한다.

```json
{
  "status": 400,
  "message": "입력값이 올바르지 않습니다",
  "detail": "price: 0 이상이어야 합니다",
  "timestamp": "2024-01-15T09:00:00Z"
}
```

| 예외 | HTTP 상태 | 처리 |
|------|-----------|------|
| `MethodArgumentNotValidException` | 400 | field errors 직렬화 |
| `HttpMessageNotReadableException` | 400 | JSON 파싱 실패 |
| `IllegalArgumentException` | 400 | 비즈니스 입력 오류 |
| `NoSuchElementException` | 404 | 리소스 없음 |
| `BadCredentialsException` | 401 | 인증 실패 |
| `AccessDeniedException` | 403 | 권한 없음 |
| `RiskLimitException` | 422 | 리스크 한도 초과 |
| `IllegalStateException` (비즈니스) | 409 | 현재가 없음, 잔고 부족 등 |
| `IllegalStateException` (서버) | 500 | 내부 컴포넌트 오류 (로그 포함) |
| `ResponseStatusException` | 해당 상태 | MSA 내부 서비스 전파 |
| `Exception` (catch-all) | 500 | 로그 + 일반 메시지 반환 |

비즈니스 규칙 위반(`현재가`, `보유`, `잔고`, `불가`, `없음` 포함 메시지)은 409로,
그 외 `IllegalStateException`은 서버 오류(500)로 분류한다.

## Security

- JWT (Access 15min + Refresh 7d), token rotation on refresh
- BCryptPasswordEncoder
- CORS restricted to `localhost:3000` / `*.monticker.io`
- Rate limiting — 2-tier 구조 (아래 참조)
- Quant ruleset: never serialised to client — server-side evaluation only
- Ruleset fingerprint (SHA-256) for tamper detection
- Signal query rate-limited (reverse-engineering prevention)

### Rate Limiting — 2-tier

**Tier 1: `RateLimitFilter` (IP 기반, 인증 전 처리)**

서블릿 필터 레이어에서 IP 주소를 기준으로 전역 제한을 적용한다.

| 경로 | 한도 | 창 | 목적 |
|------|------|----|------|
| `POST /api/auth/login` | IP당 10회 | 1분 | 브루트포스 방어 |
| `POST /api/auth/signup` | IP당 5회 | 10분 | 계정 생성 스팸 방지 |
| `POST /api/auth/refresh` | IP당 20회 | 1분 | 토큰 갱신 남용 방지 |
| `/api/auth/**` (기타) | IP당 30회 | 1분 | 인증 전반 보호 |
| `/api/**` | IP당 300회 | 1분 | 전체 API 보호 |

**Tier 2: `@RateLimited` (userId 기반, 메서드 레벨)**

AOP로 인증된 사용자별 제한을 적용한다. Redis 키: `ratelimit:{prefix}:{userId}`.
userId는 SecurityContextHolder에서 추출하므로 컨트롤러 메서드 시그니처 변경 불필요.

| 엔드포인트 | 한도 | 창 | keyPrefix |
|-----------|------|----|-----------|
| `POST /api/paper/buy` | 60회 | 1분 | `paper.buy` |
| `POST /api/paper/sell` | 60회 | 1분 | `paper.sell` |
| `POST /api/paper/reset` | 3회 | 24시간 | `paper.reset` |
| `POST /api/matching/orders` | 30회 | 1분 | `matching.order` |
| `DELETE /api/matching/orders/{id}` | 30회 | 1분 | `matching.cancel` |
| `POST /api/alerts/rules` | 20회 | 1시간 | `alert.create` |
| `GET /api/stocks/{id}/summary` | 30회 | 1시간 | `ai.summary` |
| `GET /api/wallet/emotion-analysis` | 10회 | 1시간 | `wallet.emotion` |
| `POST /api/quant/rulesets/{id}/backtest` | 10회 | 1시간 | `quant.backtest` |
| `POST /api/batch/jobs/candle-backfill` | 5회 | 1시간 | `batch.candle_backfill` |

## Graceful Shutdown

`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`가 api, worker 모두에 설정된다.

K8s가 `SIGTERM`을 보내면:
1. Tomcat이 새 요청 수락을 중단한다.
2. 최대 30초간 진행 중인 요청이 완료되길 기다린다.
3. `@Async` 스레드풀(`backtestExecutor.awaitTermination=60s`, `alertDispatchExecutor.awaitTermination=10s`)도 graceful하게 종료된다.

## Kafka Dead Letter Topic (DLT)

Spring Kafka `@RetryableTopic`을 사용해 소비 실패 시 자동 재시도 후 DLT로 이동한다.

| 토픽 | 재시도 횟수 | 백오프 | DLT 토픽 |
|------|------------|--------|----------|
| `market.ticks` | 3회 | 2s × 2 배 | `market.ticks-dlt` |
| `market.tick-processed` | 3회 | 1s × 2 배 | `market.tick-processed-dlt` |
| `trading.order-filled` | 4회 | 3s × 2 배 | `trading.order-filled-dlt` |

DLT 핸들러(`@DltHandler`)는 ERROR 레벨 로그를 남긴다. 재처리는 수동 검토 후 DLT 토픽에서 재발행한다.

## Idempotency Key

`X-Idempotency-Key` 헤더로 멱등성을 보장한다. 중복 주문(네트워크 재시도)을 방지한다.

| 엔드포인트 | 적용 | TTL |
|-----------|------|-----|
| `POST /api/paper/buy` | ✅ | 24시간 |
| `POST /api/paper/sell` | ✅ | 24시간 |
| `POST /api/matching/orders` | ✅ | 24시간 |

Redis 키: `idempotency:{userId}:{X-Idempotency-Key}`. 2xx 응답만 캐싱한다.

## Bulkhead — Backtest 격리

`BacktestController`는 `backtestExecutor`(core=2, max=4, queue=20) 전용 스레드풀로 실행된다.
CPU-heavy 백테스트 급증이 주문 처리 API 스레드풀에 영향을 주지 않는다.
queue 초과 시 `RejectedExecutionException` → HTTP 429 반환.

## Distributed Lock — 스케줄러 중복 실행 방지

Redis `SETNX` 기반 `@DistributedLock` AOP를 스케줄러에 적용한다.
K8s 레플리카 2개 이상에서 동일 수집 작업이 중복 실행되는 것을 방지한다.

| 스케줄러 | 락 이름 | TTL |
|---------|---------|-----|
| `NewsCollector.collect` | `news-collector` | 1,500s |
| `DisclosureCollector.collect` | `disclosure-collector` | 540s |

## Request ID (Correlation ID)

`RequestIdFilter`(Order=1)가 모든 요청에 실행된다.
- `X-Request-Id` 헤더가 있으면 재사용, 없으면 UUID 생성.
- MDC에 `requestId`로 등록 → 모든 로그에 자동 포함.
- 응답 헤더 `X-Request-Id`로 클라이언트에 반환.

## Outbox Pattern

Spring Modulith Events를 활용해 이벤트 유실 없는 at-least-once Kafka 발행을 보장한다.

### 동작 흐름

```
MatchingService.submitOrder()  [트랜잭션 시작]
  ├─ fills 테이블 INSERT
  ├─ paper_accounts 잔고 UPDATE
  └─ event_publication 테이블 INSERT  ← 같은 트랜잭션 (Outbox)
                                          [트랜잭션 커밋]
                                              │
                                    Modulith EventPublisher
                                              │
                             ┌────────────────┴──────────────────┐
                             ▼                                   ▼
                  Kafka 발행                          In-process listeners
              trading.order-filled              OrderFilledEventListener (원장)
              trading.order-cancelled           OrderFilledStrategyListener (quant)
                             │
                    completion_date 기록
                    (event_publication UPDATE)
```

### 유실 방지 메커니즘

| 상황 | 처리 |
|------|------|
| 커밋 전 앱 크래시 | 트랜잭션 롤백 → event_publication도 롤백 → 이벤트 없음 (정상) |
| 커밋 후 Kafka 장애 | event_publication에 `completion_date = NULL` 유지 |
| 앱 재시작 | 기동 시 미완료 이벤트 자동 재전송 |
| Kafka 간헐적 오류 | `OutboxResubmissionConfig`가 5분마다 1분 이상 미완료 이벤트 재전송 |

### 외부화 대상 이벤트

| 이벤트 | Kafka 토픽 | 키 |
|-------|------------|-----|
| `OrderFilledEvent` | `trading.order-filled` | `userId` |
| `OrderCancelledEvent` | `trading.order-cancelled` | `userId` |

### 프로듀서 설정

- `acks=all` — 모든 ISR replica 확인 후 ACK
- `enable.idempotence=true` — 네트워크 재시도로 인한 중복 발행 방지
- `retries=3` — 일시 장애 시 자동 재시도
