# monticker — Architecture

> Read this when: designing a module, adding a new table, wiring up a new worker, or making any infrastructure decision.

**Product stage:** MVP is complete; monticker is now in active commercialization ([ADR-023](decisions/023-commercialization-pivot.md)). Real brokerage order execution (BYOK via Toss/KIS Open API) and real market-data feeds are on the near-term roadmap, not out of scope — design new modules to be production-safe (concurrency, credential handling, resilience) from the start.

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

Watch rule([ADR-051](decisions/051-event-triggered-paper-orders.md))이 이 파이프라인의 끝에 **행동**을 붙인다 —
탐지 로직은 그대로 두고 소비자만 하나 늘린다:

```
stock_events INSERT (worker)
  → @Externalized StockEventDetectedEvent   (같은 트랜잭션, 커밋 후 외부화)
  → Kafka market.event-detected (key=stockId)
  → WatchRuleConsumer (api, groupId=monticker-watch-rule)
  → WatchRuleExecutor — 활성 룰 조회 → 중요도/쿨다운 판정
  → matching::submit (리스크 게이트 통과) → 모의투자 체결
  → watch_rule_executions 기록 (EXECUTED / REJECTED / SKIPPED + 사유)
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

Start as **Modular Monolith + async workers + Redis + TimescaleDB**. `docker compose --profile msa up` splits the worker into three role-based processes (see [MSA Architecture](#msa-architecture) below) — the separate quant-engine/trading-service microservices were retired ([ADR-048](decisions/048-retire-trading-service.md)/[ADR-049](decisions/049-retire-quant-engine.md)).

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

### 역할 분리 모드 (`make up-msa`) — 워커 3종 + Kafka. (quant-engine·trading-service는 [ADR-048](decisions/048-retire-trading-service.md)/[ADR-049](decisions/049-retire-quant-engine.md)로 폐기)

```
[Next.js :3000]  [Expo Mobile]
        │  REST / WebSocket
        ▼
┌─────────────────────────────────────┐
│       backend/api  :8080            │
│  JWT Auth · 모듈러 모놀리스           │
│  quant·analytics·backtest (bulkhead)│
│  paper · matching · wallet (in-proc)│
│  @Externalized → order-filled       │
└──────────────────────────┬──────────┘
                           │ PRODUCE (Modulith outbox)
       │CONSUME             ▼
       ╔══════════════════════════════════════════════════════╗
       ║       Apache Kafka  :9092 / :29092                   ║
       ║  market.ticks │ tick-processed │ order-filled │ ...  ║
       ╚═╦═══════╦════╦═══════════════════╝
         │       │    │
       PUB     SUB  PUB SUB
         │       │    │    │
  ┌──────┴──┐ ┌──▼────┴──┐ ┌──▼──────┐ ┌───────────────┐
  │worker-  │ │worker-   │ │worker- │ │market-gateway │
  │market   │ │event     │ │alert   │ │(Go)           │
  └─────────┘ └──────────┘ └────────┘ └───────────────┘
                 │ JDBC + Redis (shared)
        ┌────────┴──────────────────┐
        │  TimescaleDB :5432        │   Redis :6379
        └───────────────────────────┘
```
실시간 시세 푸시(브라우저까지)는 이 다이어그램 밖의 별도 경로다 — `backend/api`의
`PriceBroadcaster`가 `market.ticks`를 직접 구독해 STOMP(`/topic/stocks/{id}`)로 발행한다
(ADR-029). 여기 있던 Netty 기반 커스텀 WebSocket 브로드캐스트 게이트웨이는 프론트엔드
클라이언트가 한 번도 존재한 적이 없어 ADR-033으로 제거됐다.

---

## Tech Stack

> 각 기술을 왜 골랐는지, 근거가 어디에 있는지는 [tech-stack-decisions.md](tech-stack-decisions.md)에 있습니다. 아래 버전 표기 중 Kotlin(실제 1.9.25)·Batch(실제 Spring Batch)·MongoDB 용도(실제 `rule_sets`만)는 코드와 다릅니다 — 같은 문서 §10 참고.

### Backend

```
Language:    Kotlin 2.0
Framework:   Spring Boot 3.5
Pattern:     Modular Monolith
API:         REST + WebSocket (STOMP over SockJS)
ORM:         Spring Data JPA
Migration:   Flyway (V1–V56)
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
Chart:       Apache ECharts 6 (via chart adapter — see technical/chart-adapter-pattern.md)
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
Time-series:      TimescaleDB (candles_1m, candles_1d hypertables — raw ticks are not stored, ADR-041)
Cache / Realtime: Redis 7
Documents:        MongoDB 7 (rule_sets 문서, alert_histories) — MONGODB_URI
Search:           Elasticsearch (검색 인덱스, DB 폴백) — see elasticsearch.md
```

### Infra

```
Local:      Docker Compose
CI/CD:      GitHub Actions
Tracing:    Jaeger (all-in-one)
```

### Realtime Pipeline

```
Ingestion:  Go (goroutine-per-stock tick generator/gateway)
Bus:        Kafka (KRaft mode, single broker)
```

See [ADR-005](decisions/005-kafka-go-gateway-netty-broadcast.md) and [kafka-tick-pipeline.md](technical/kafka-tick-pipeline.md). All tick paths go through Kafka: `MockPriceGenerator`(worker), KIS/Toss realtime handlers and the Go gateway all produce to `market.ticks`. Each tick carries its `source` (`KIS`/`TOSS`/`MOCK`) so that real-money consumers can refuse synthetic prices ([ADR-055](decisions/055-price-provenance-gate-for-real-orders.md)). Real-time browser push (STOMP, always on regardless of this profile) is handled separately by `PriceBroadcaster` in `backend/api` — see [ADR-029](decisions/029-price-broadcast-pipeline.md). ADR-005 originally also introduced a Netty-based custom WebSocket broadcast gateway here; it was removed in [ADR-033](decisions/033-remove-netty-broadcast-gateway.md) after never gaining a frontend client.

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
| **Watch Rule** | `watch_rules`, `watch_rule_executions` | Done ([ADR-051](decisions/051-event-triggered-paper-orders.md)) — 탐지 이벤트 → 모의 자동 주문, 모의계좌 전용 |
| Paper Settlement | `paper_settlements` | Done — T+2 배치 ([ADR-014](decisions/014-t2-paper-settlement-scheduler.md)) |
| Backtest | `backtest_results` | Done — 내장 전략 3종, `backtestExecutor` bulkhead |
| Disclosure | `disclosures` (DART 수집) | Done |
| Device | `device_tokens` | Done — Expo 푸시 토큰 |
| Investor Flow | `investor_flow` | Done ([ADR-017](decisions/017-investor-flow-kis-integration.md)) — 국내 종목 한정 |
| Stock Score | `stock_fundamentals` | Done ([ADR-020](decisions/020-stock-valuation-score.md)) — 국내 종목 한정 |
| AI | `order_proposals` | Done ([ADR-036](decisions/036-ai-order-proposal.md)) — 방향·근거만 제안, 제출은 사용자 |
| Community | `stock_comments`, `stock_comment_reports` | Done ([ADR-037](decisions/037-stock-community-comments.md)) — 매수·매도 권유 fail-closed 차단 |
| **Brokerage (BYOK)** | `brokerage_accounts`, `brokerage_orders`, `brokerage_settlements`, `conditional_orders`, `rebalance_*` | 🟡 코드 완료 / 실계좌 미검증 — KIS·토스증권·Mock ([ADR-023](decisions/023-commercialization-pivot.md), [ADR-025](decisions/025-real-brokerage-order-safety-gate.md)) |
| **Subscription** | `subscription_plans`, `user_subscriptions`, `payment_records`, `user_billing_keys` | 🟡 코드 완료 / 라이브 결제 미검증 — 토스페이먼츠 ([settlement.md](settlement.md)) |
| **Settlement (Creator)** | `creator_earnings`, `creator_payouts` | 🟡 적립·승인까지 — 실제 송금은 코드 밖 |
| Batch | Spring Batch 메타테이블 | Done — 정산·갱신·대조·백필 잡 (`batch/BatchJobScheduler`) |

### Implemented Modules (Quant Lab — V13)

| Module | Tables | Status |
|--------|--------|--------|
| **Rule Builder** | `rule_sets` | Done — Ruleset CRUD, condition JSON, version management |
| **Rule Engine** | `quant_signals` | Done — Evaluate conditions against live indicators; emit signals |
| **Indicator Engine** | (in-memory) | Done — MA, EMA, RSI, MACD, Bollinger, ATR from candle data |
| **Backtest Engine** | `backtest_results` | Done — Historical simulation, commission/slippage, reliability score |
| **Forward Test Engine** | `quant_forward_tests`, `quant_forward_test_equity`, `quant_signals` | Done ([ADR-024](decisions/024-quant-lab-forward-test.md)) — daily post-close cron evaluation, live signal push via `/topic/rulesets/{id}/signals` |
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

> 2026-10-04 갱신. 이전 판은 `MockPriceGenerator`가 디텍터를 직접 부르고 `AlertEvaluator`가 30초 스케줄로 도는
> 구조를 그렸다 — 둘 다 이제 사실이 아니다.

```
틱 생산자 — 모두 Kafka market.ticks (key=stockId) 로 produce, 틱마다 source 태그 (ADR-055)
  ├── KisExecutionTickHandler   (KIS H0STCNT0 실시간 체결, 커버 종목)      source=KIS
  ├── TossExecutionTickHandler  (Toss trade 채널, 커버 종목)              source=TOSS
  ├── MockPriceGenerator        (@Scheduled 1s, KIS/Toss 미커버 종목만)   source=MOCK
  └── Go market-gateway         (선택, 합성 생성기)                        source=MOCK
        │
        ▼
TickKafkaConsumer (worker role=event)
  ├── RedisTickWriter        SET stock:price:{market}:{symbol}  (TTL 없음)
  ├── CandleAggregator       인메모리 1분 버퍼 → candles_1m / candles_1d upsert (ADR-021)
  ├── EventDetector          PriceSpike / VolumeSurge — 상태는 프로세스 메모리 (ADR-046)
  │     └── INSERT stock_events (분 버킷 유니크) → @Externalized market.event-detected (ADR-051)
  └── produce market.tick-processed
        │
        ▼
AlertEvaluator (worker role=alert, @EventListener @Async on TickProcessedEvent)
  ├── AlertRuleIndex         종목별 인메모리 룰 인덱스, Redis pub/sub 무효화 + 5분 delta sync (ADR-044)
  └── AlertDispatcher        10분 쿨다운 (Redis alert:cooldown:{ruleId}) → alert_histories → Expo push
``` See [kafka-tick-pipeline.md](technical/kafka-tick-pipeline.md). Browser-facing real-time push is a separate path — `backend/api`'s `MarketTickBroadcastConsumer` consumes `market.ticks` independently (its own consumer group) and forwards via STOMP; see [ADR-029](decisions/029-price-broadcast-pipeline.md).

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
ForwardTestScheduler (@Scheduled 16:00 KST, MON-FRI — ADR-024, 틱 단위가 아니라 일봉 단위)
  → ForwardTestService: candles_1d 로드
  → IndicatorEngine.compute(...)                  (MA, EMA, RSI, MACD, Bollinger, ATR)
  → RuleEvaluator.evaluate(ruleSet, indicators)   ← never sends ruleset to client
  → quant_signals INSERT + 포워드 테스트 포지션/자산곡선 갱신
  → STOMP /topic/rulesets/{id}/signals            (구독자만 — RuleSetSignalAccessInterceptor, ADR-035)

자동 매매(PaperAutoTrader) 경로는 없다. 이벤트 기반 모의 자동주문은 Watch Rule(ADR-051)이 맡는다.
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
Ruleset stored in MongoDB rule_sets (RuleSetDocument.ruleDefinition) — 평문이다. 암호화 저장은 구현되지 않았다.
Fingerprint: SHA-256(normalize(ruleDefinition)) — 필드는 있으나 구독/신호 경로에서 검증에 쓰이지 않는다
             (engineering-backlog §5).

GET /api/strategies/{id}/signal   (subscriber endpoint)
  → server evaluates the ruleset (never sent to the client)
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
| … | (V17–V47 — 상세는 `backend/api/src/main/resources/db/migration/`) |
| V48 | Add `orders.idempotency_key` + 부분 유니크 인덱스 ([ADR-051](decisions/051-event-triggered-paper-orders.md)) |
| V49 | Create Watch Rule tables (watch_rules, watch_rule_executions) |

---

## API Endpoints

### REST

> **정본은 컨트롤러다.** 이 목록은 2026-09-30 기준으로 `*Controller.kt`에서 기계적으로 추출한 것이며,
> 경로가 의심스러우면 `backend/api/src/main/kotlin/com/monticker/api/**/api/`를 본다.
> 과거 이 표가 수기 관리되다가 실제로는 없는 경로 16개를 담고 있었다(`/api/paper/orders`,
> `/api/wallet/timeline`, `/api/stocks/{id}/patterns` 등) — 손으로 덧붙이지 말 것.

```http
# 인증 · 계정
POST   /api/auth/signup | login | logout | refresh
POST   /api/auth/verify-email | resend-verification | forgot-password | reset-password
POST   /api/auth/mock-social                  # SOCIAL_MOCK_ENABLED=true 일 때만
DELETE /api/auth/account
GET    /api/users/me/notification-preferences
PUT    /api/users/me/notification-preferences

# 종목 · 시세
GET    /api/stocks/search
GET    /api/stocks/{stockId}
GET    /api/stocks/{stockId}/price | candles | orderbook | vwap | vwap/series
GET    /api/market/summary
GET    /api/latency
GET    /api/screener | /api/screener/quotes | /api/screener/search
GET    /api/screener/sectors/performance  # 섹터 등락률(동일가중), ADR-087

# 이벤트 · 뉴스 · 공시 · AI 요약
GET    /api/stocks/{stockId}/events | news | disclosures
GET    /api/events/recent | /api/events/search | /api/sectors/events
GET    /api/events/summary | /api/events/counts   # KST 하루 유형별 집계, 종목별 기간 건수 (ADR-087)
GET    /api/news/search | /api/disclosures/search | /api/summaries/search
GET    /api/stocks/{stockId}/summary          # AI 요약
GET    /api/stocks/{stockId}/score            # 밸류에이션 (국내 한정, ADR-020)
GET    /api/stocks/{stockId}/investor-flow    # 투자자 동향 (국내 한정, ADR-017)

# 관심종목 · 알림 · 디바이스
GET    /api/watchlists | /api/watchlists/search
POST   /api/watchlists/groups | /api/watchlists/groups/{groupId}/items
DELETE /api/watchlists/items/{itemId}
GET    /api/alerts/rules | /api/alerts/stats | /api/alerts/history/search
POST   /api/alerts/rules
DELETE /api/alerts/rules/{ruleId}
POST   /api/devices/push-token
DELETE /api/devices/push-token

# 모의투자 · 체결엔진 · 리스크
GET    /api/paper/portfolio | history | risk
POST   /api/paper/buy | sell | reset
POST   /api/matching/orders                   # 리스크 게이트 → CLOB
DELETE /api/matching/orders/{id}
GET    /api/matching/orders | /api/matching/fills | /api/matching/orders/{id}/fills
GET    /api/risk/limits | /api/risk/exposure
PUT    /api/risk/limits
POST   /api/risk/check                        # dry-run

# 투자 지갑 (원장)
GET    /api/wallet                            # 돈의 이동 지도
GET    /api/wallet/ledger | replay | score | emotion-analysis
GET    /api/wallet/{id}/receipt
GET    /api/wallet/{id}/emotion
POST   /api/wallet/{id}/emotion

# 자동 주문 규칙 (ADR-051) — 모의계좌 전용
GET    /api/watch-rules | /api/watch-rules/executions
POST   /api/watch-rules
PATCH  /api/watch-rules/{ruleId}
DELETE /api/watch-rules/{ruleId}

# Quant Lab · 전략 마켓
GET    /api/quant/rulesets | /{id} | /{id}/versions | /{id}/backtest | /{id}/forward-test
POST   /api/quant/rulesets | /{id}/backtest | /{id}/forward-test/start | /{id}/forward-test/stop
PUT    /api/quant/rulesets/{id}
DELETE /api/quant/rulesets/{id}
GET    /api/quant/market
POST   /api/quant/market/share | /api/quant/market/{id}/subscribe
DELETE /api/quant/market/{id}/subscribe
GET    /api/backtest/strategies                # 내장 전략 목록
POST   /api/backtest                           # 내장 전략 실행

# Quant Analytics
GET    /api/analytics/portfolio/optimize | frontier
GET    /api/analytics/position-size/kelly      # POST 도 지원
GET    /api/analytics/tax/harvesting-candidates
GET    /api/analytics/{stockId}/patterns | regime

# AI 주문 제안 (ADR-036) — 방향·근거만, 제출은 사용자
GET    /api/ai/order-proposals | /{id}
POST   /api/ai/order-proposals | /{id}/approve | /{id}/reject

# 종목 커뮤니티 (ADR-037)
GET    /api/stocks/{stockId}/comments
POST   /api/stocks/{stockId}/comments | /{id}/report
DELETE /api/stocks/{stockId}/comments/{id}

# 실전투자 (BYOK) — 기본 Mock, BROKERAGE_MOCK_ENABLED
POST   /api/brokerage/connect
GET    /api/brokerage/account | account/balance
POST   /api/brokerage/orders
GET    /api/brokerage/orders | orders/active | orders/{id}/sync
DELETE /api/brokerage/orders/{id}
GET    /api/brokerage/settlements | settlements/pending
GET    /api/brokerage/conditional-orders
POST   /api/brokerage/conditional-orders | conditional-orders/oco
DELETE /api/brokerage/conditional-orders/{id}
GET    /api/rebalance/target | preview | executions
POST   /api/rebalance/target | execute

# 정산 · 구독 · 제작자 수익
GET    /api/settlement/paper | paper/pending | paper/trade/{tradeId}
GET    /api/settlement/strategy/earnings | earnings/summary | payouts
POST   /api/settlement/strategy/payout
GET    /api/subscription/plans | me | payments
POST   /api/subscription/subscribe | cancel
POST   /api/subscription/payment/confirm | payment/webhook
GET    /api/subscription/billing | billing/customer-key
POST   /api/subscription/billing/register
DELETE /api/subscription/billing

# 운영 (관리자)
POST   /api/admin/batch/behavior-score | candle-backfill | ledger-reconciliation | regime
GET    /api/admin/search/indices
POST   /api/admin/search/reindex/{index}
GET    /health
```

**설계만 있고 구현되지 않은 것**: `GET /api/strategies/{id}/signal`(구독자용 신호 조회)은
[Strategy Protection](#strategy-protection) 절에 설계가 적혀 있지만 컨트롤러가 없다 — 지금 신호는
STOMP `/topic/rulesets/{id}/signals`로만 나간다([ADR-035](decisions/035-strategy-market-signal-access-control.md)).

### WebSocket (STOMP)

Connect: `ws://localhost:8080/ws` (SockJS fallback)

| Topic | Description |
|-------|-------------|
| `/topic/stocks/{stockId}` | 종목별 실시간 가격 |
| `/topic/market/summary` | 시장 요약, 1초 1회 ([ADR-039](decisions/039-drop-global-market-topic.md) — 전역 `/topic/market`은 폐기) |
| `/topic/rulesets/{ruleSetId}/signals` | 포워드 테스트 매수/매도 신호 알림 (Quant Lab, ADR-024) |

---

## Brokerage Adapter — BYOK Model

monticker does not hold its own brokerage license. Real order execution always runs against the end user's own linked brokerage account — monticker is an API client acting on the user's behalf with the user's own credentials ("bring your own key"), never a broker itself. See [ADR-023](decisions/023-commercialization-pivot.md).

```
BrokerageService (api/brokerage/application)
      │  depends on interface only
      ▼
BrokerageClient  (interface — broker-agnostic DTOs: order/status/settlement/balance)
      │
      ├── MockBrokerageClient   @Primary in dev (app.brokerage.mock.enabled=true)
      ├── KisBrokerageClient    한국투자증권 Open API — user-issued appKey/appSecret
      └── TossBrokerageClient   토스증권 Open API — planned, same interface
```

- **Provider selection is config-driven** (`app.brokerage.mock.enabled`, per-user provider choice), never hardcoded — `BrokerageService` never knows which broker it's talking to.
- **Every implementation must register a named resilience4j circuit breaker** (see [Circuit Breaker](#circuit-breaker) below) the way `YahooFinanceOrderBookProvider` does. `KisBrokerageClient` (`"kis"`) and `TossBrokerageClient` (`"toss"`) are both registered in `common/resilience/CircuitBreakerConfiguration`; a new provider must add its own.
- **User-supplied broker credentials (appKey/appSecret) are encrypted at rest** via `EncryptedStringConverter` (AES-256-GCM, `common/security/`) applied to `BrokerageAccount.accessToken`. Key comes from `app.security.credential-encryption-key` — production must override the dev default.
- **Cash reservation is safe under concurrency.** `OrderSagaOrchestrator.reserveCash` does the balance check and the debit in one atomic `UPDATE ... WHERE cash >= ?` instead of a separate SELECT-then-UPDATE — proven under real concurrent load in `CashReservationConcurrencyIntegrationTest` (Testcontainers Postgres, 10 concurrent threads against a shared account).

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
stock:price:{market}:{symbol}      # 최신 시세 JSON (STRING)
orderbook:{symbol}                 # KIS 실시간 호가 (STRING, TTL 30s)
alert:cooldown:{ruleId}            # 알림 쿨다운 플래그 (STRING, TTL 600s)
alert:rules:changed                # 알림 룰 인메모리 인덱스 무효화 신호 (ADR-044)
ratelimit:{prefix}:{userId}        # @RateLimited 카운터 (TTL = window)
idempotency:{userId}:{key}         # X-Idempotency-Key 응답 캐시 (TTL 24h)
```

Redis 실패 시 동작은 용도별로 다르다 — 레이트리밋·캐시는 **fail-open**, 멱등성은 **fail-closed**(503).
`RedisGuard`가 이 정책을 강제하고 `redis_command_failed_total{op,policy}`로 관측한다
([resilience-plan P0-1](resilience-plan.md), [runbooks/redis-down.md](runbooks/redis-down.md)).

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
| 3 | ~~Extract `quant-engine` as standalone service (:8082)~~ — 추출은 됐으나 위임이 연결된 적 없어 트래픽 0. L-06 실측으로 in-process bulkhead가 충분함을 확인하고 **폐기**([ADR-049](decisions/049-retire-quant-engine.md)) | ❌ retired |
| 4 | Kafka always-on — all ticks route through `market.ticks` | ✅ implemented |
| 5 | ~~Extract `trading-service` (:8083)~~ — 추출은 됐으나 api가 위임을 연결한 적이 없어 4개월간 트래픽 0. **폐기**([ADR-048](decisions/048-retire-trading-service.md)). matching은 api 안의 모듈이며, 체결 이벤트는 api의 Modulith 외부화(`@Externalized`)가 발행한다 | ❌ retired |

Stage 6 이후(대규모 트래픽·데이터 가정)는 [scale-out-plan.md](scale-out-plan.md)에서 다룬다 —
현재 구조에서 먼저 깨지는 지점(인메모리 STOMP 브로커, `/topic/market` 전역 브로드캐스트,
Kafka 단일 파티션, 미가동 hypertable 등)의 인벤토리와 Phase 0~4 전환 계획.

---

## MSA Architecture

Full diagram: see [MSA Architecture Diagram (Artifact)](https://claude.ai/code/artifact/afddd890-919c-46cb-8d4a-37547d5e18fc)

### Deployment

```bash
# 역할 분리 워커 기동 (Kafka + worker-market/event/alert) — quant-engine·trading-service는 ADR-048/049로 폐기
make up-msa

# 단일 프로세스 모드 (Kafka + api + worker(role=all))
make up-full
```

### Service Ports

| Service | Port | Docker Profile | Role |
|---------|------|---------------|------|
| `backend/api` | 8080 | `full` / `msa` | API gateway, JWT auth, 모든 도메인 모듈 (strangler-fig proxy는 폐기 — 아래 참고) |
| `kafka` | 9092 / 29092 | `full` / `kafka` / `msa` | event bus |
| `postgres` (TimescaleDB) | 5432 | always | shared DB |
| `redis` | 6379 | always | tick cache, candle, orderbook |
| `grafana` | 3001 | — | observability dashboard |
| `jaeger` | 16686 | — | distributed tracing |

### Kafka Topics

| Topic | Producer | Consumer |
|-------|----------|----------|
| `market.ticks` | `worker-market`, `market-gateway` (Go) | `worker-event`(그룹 `monticker-worker`), `backend/api` **컨슈머 그룹 없이 전 파티션 수동 할당**([ADR-038](decisions/038-broadcast-consumer-partition-assignment.md)) — STOMP push + [ADR-032](decisions/032-conditional-orders.md) 조건부 주문 평가 |
| `market.tick-processed` | `worker-event` | `worker-alert` |
| `market.events` | `worker-event` (`ingestion.source=kafka` 일 때만) | **없음** — [ADR-033](decisions/033-remove-netty-broadcast-gateway.md)으로 Netty 게이트웨이가 사라진 뒤 주인이 없다. 자동 주문은 아래 `market.event-detected`를 쓴다([ADR-051](decisions/051-event-triggered-paper-orders.md) 참고) |
| `market.summary` | `worker` `MarketSummaryPublisher` | `backend/api` `MarketSummaryBroadcastConsumer` (틱과 같은 수동 할당 방식) |
| `notify.commands` | `worker-event` `NotifyKafkaProducer` ([ADR-044](decisions/044-alert-rule-in-memory-index.md)) | `worker-alert` — 평가와 발송을 분리해 발송 지연이 틱 파이프라인을 막지 않게 한다 |
| `notify.user` | api `UserNotificationCommand` — Modulith 아웃박스([ADR-065](decisions/065-user-notifications-from-api.md)) | `worker-alert` `UserNotifyKafkaConsumer` — 조건부 주문 발동 실패 등 사용자 알림(푸시, 없으면 이메일) |
| `trading.order-filled` | `backend/api` (Modulith `@Externalized`, Outbox) | (현재 없음 — quant live-tracking 도입 시. api 안에서는 `OrderFilledStrategyListener`가 같은 이벤트를 받는다) |
| `trading.order-cancelled` | `backend/api` (Modulith `@Externalized`, Outbox) | — |
| `search.index` | `backend/api`, `worker` (Modulith `@Externalized`, [ADR-042](decisions/042-outbox-based-es-indexing.md)) | `backend/api` `SearchIndexConsumer` |
| `market.event-detected` | `worker` (Modulith `@Externalized`, [ADR-051](decisions/051-event-triggered-paper-orders.md)) | `backend/api` `WatchRuleConsumer` (group `monticker-watch-rule`) |

### MSA Key Design Decisions

- **Strangler-fig proxy — 폐기됨**: `QuantEngineClient`·`TradingServiceClient`는 만들어졌을 뿐 어느 컨트롤러도 부르지 않았고 nginx는 `/api/**` 전부를 api로 보낸다. 두 서비스 모두 트래픽 0으로 확인돼 제거했다([ADR-048](decisions/048-retire-trading-service.md), [ADR-049](decisions/049-retire-quant-engine.md)). 분석 경로 격리는 `backtestExecutor` bulkhead가 맡는다(L-06 실측).
- **Distributed transaction safety**: order events are `@Externalized` Spring Modulith events ([ADR-008](decisions/008-outbox-pattern-spring-modulith.md)) — recorded in `event_publication` inside the matching transaction, published to Kafka after commit, resubmitted every 5 minutes if publishing failed. Verified under a broker outage in CH-05 ([resilience-plan §6.3](resilience-plan.md)).
- **Worker role activation**: `@ConditionalOnExpression("'${worker.role:all}'.matches('market|all')")` activates components per role. The `all` default keeps the monolith worker behaviour.
- **Tick ingestion dual-path**: `ingestion.source=internal` (default) → `MockPriceGenerator` → Kafka. `ingestion.source=kafka` → Go `market-gateway` → Kafka. Both paths converge at `market.ticks`.

---

## Circuit Breaker

Resilience4j Circuit Breaker를 외부 HTTP 호출 지점마다 적용한다.
OPEN 상태에서는 즉시 `null`을 반환해 **로컬 폴백** 경로로 전환되므로 타임아웃 누적으로 인한 쓰레드 풀 고갈을 막는다.

### Worker — `resilience/CircuitBreakerRegistry` (worker 모듈)

| CB 이름 | 대상 | 실패율 임계 | 창 | OPEN 대기 | 폴백 |
|---------|------|-----------|-----|---------|------|
| `kisApi` | `KisClient` (KIS 실시세) | 50% | 10회 | 30초 | `MockPriceGenerator` |
| `expoPush` | `ExpoPushSender` (Expo Push) | 60% | 5회 | 60초 | 빈 결과 반환 |
| `naverNews` | `NaverNewsClient` (뉴스 API) | 50% | 4회 | 5분 | `MockNewsGenerator` |
| `dartApi` | `DartClient` (DART 공시 API) | 50% | 4회 | **10분** | 빈 리스트 반환 |

### API — `common/resilience/CircuitBreakerConfiguration` (api 모듈)

| CB 이름 | 대상 | 실패율 | slow-call | 창 | OPEN 대기 | 폴백 |
|---------|------|-------|-----------|-----|---------|------|
| `yahooFinance` | `YahooFinanceOrderBookProvider`, `YahooCandleReader` | 60% | 50% / 3초 | 5회 | 2분 | `null` → 다음 프로바이더 |
| `kis` | `KisBrokerageClient` (실주문·잔고·정산) | 50% | 50% / 3초 | 6회 | 30초 | 예외 → 503 (실주문은 조용히 실패시키지 않는다) |
| `toss` | `TossBrokerageClient` ([ADR-026](decisions/026-toss-brokerage-integration.md)) | 50% | 50% / 3초 | 6회 | 30초 | 위와 동일 |

> **모든 브레이커에 `slowCallRateThreshold`가 걸려 있다**(resilience-plan §B1 / P0-2). 실패율만 보면
> 외부가 "죽었을 때"만 열린다 — 죽지 않고 느려지는 쪽이 스레드 풀에는 더 위험하다.
> `slowCallDurationThreshold`는 `HttpTimeouts`의 read 타임아웃보다 짧게 둔다.

> 과거 여기에 `tradingService`·`quantEngine` 브레이커가 있었다. 두 MSA 프록시가
> [ADR-048](decisions/048-retire-trading-service.md)/[ADR-049](decisions/049-retire-quant-engine.md)로
> 폐기되면서 함께 제거됐다.

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
| `POST /api/watch-rules` | 20회 | 1시간 | `watchrule.create` |
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

### 서버 내부 발행 주문의 멱등성 (ADR-051)

위 필터는 **바깥에서 들어온 요청**만 보호한다. 서버가 이벤트를 소비해 스스로 내는 주문(watch rule)은
필터를 타지 않는데 아웃박스는 at-least-once다 — 그래서 멱등 키를 주문 행으로 내렸다.

```
OrderSubmitter.submitMarket(..., idempotencyKey = "WR:{ruleId}:{eventId}")
  → orders.idempotency_key 부분 유니크(V48)
  → 같은 키 재제출 시 새 주문·새 체결 없이 첫 체결을 replay
```

사용자가 화면에서 낸 주문은 키가 null이고 기존 필터가 계속 담당한다.

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

## API Gateway

외부 트래픽 진입점은 환경에 따라 다르다.

| 환경 | 게이트웨이 | 파일 |
|------|----------|------|
| 로컬 (docker-compose) | NGINX | `infra/docker/nginx/nginx.conf` |
| K8s | NGINX Ingress Controller | `infra/k8s/base/ingress.yaml` |

### 공통 기능

| 기능 | 구현 |
|------|------|
| **Rate Limiting** | K8s: `limit-rps=60`, `limit-connections=20` (IP 기반, burst×5 허용) |
| **Request ID** | `X-Request-Id` 헤더 주입 — 클라이언트 값 우선, 없으면 UUID 생성 |
| **Real IP 전달** | `X-Forwarded-For` → 앱 `ClientIpResolver`가 신뢰 프록시(`app.http.trusted-proxies`)를 거친 경우에만 오른쪽부터 해석 ([ADR-084](decisions/084-client-ip-from-trusted-proxies.md)). `RateLimitFilter`·`AuditAspect`가 사용 |
| **WebSocket** | `/ws` 경로에 Upgrade/Connection 헤더 처리, 타임아웃 3600s |
| **Security Headers** | `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` |
| **CORS** | Ingress 수준에서 허용 origin/method/header 제어 |
| **업스트림 정보 차단** | `X-Powered-By`, `Server` 헤더 숨김 |

### 라우팅

```
Client
  │
  ▼
NGINX (Gateway)
  ├─ /api/**  → api:8080  (Spring Boot)
  ├─ /ws/**   → api:8080  (WebSocket/STOMP)
  └─ /**      → web:3000  (Next.js)

api 내부: 모든 도메인 모듈이 프로세스 안에서 돈다 (ADR-048/049 — 위임 대상 서비스 없음)
```

2-tier Rate Limiting: Gateway(Ingress, 60rps/IP) → 앱(RateLimitFilter, 300req/min/IP + @RateLimited userId)

## Service Discovery

별도 서비스 레지스트리(Consul, Eureka) 없이 **K8s DNS**를 Service Discovery로 사용한다.

### K8s 환경 (prod/staging)

api가 HTTP로 호출하는 내부 서비스는 지금 없다(ADR-048/049). K8s DNS 서비스 탐색은 인프라 컴포넌트(postgres·redis·kafka·
elasticsearch)와 worker→api 방향에 쓰인다 — 형식은 `http://{k8s-service-name}:{port}`, ConfigMap에 축약형으로.

### Readiness Probe = 서비스 헬스체크

K8s Readiness Probe(`/actuator/health/readiness`)가 Pod가 트래픽을 받을 준비가 됐는지 판단한다.
Readiness 실패 시 K8s Service의 엔드포인트 목록에서 제거되어 트래픽이 라우팅되지 않는다. 이것이 K8s 환경의 동적 Service Discovery 메커니즘이다.

---

## 확장성 패턴

### Bloom Filter — 뉴스 URL 중복 제거

| 항목 | 값 |
|------|----|
| 위치 | `backend/worker` — `NewsBloomFilter` |
| 구현 | Guava `BloomFilter<String>`, in-memory |
| 용량 | 2,000,000 URL (종목 5,000 × URL 400) |
| FPP | 1% (False Positive = 뉴스 누락, 데이터 손실 없음) |
| 초기화 | `@PostConstruct` — 최근 90일 URL을 DB에서 로드 |
| 흐름 | `mightContain()` → true: DB 조회 건너뜀 / false: INSERT 시도 → 성공 시 `put()` |
| 통계 | `hits`(중복 판정 수) / `misses`(신규 판정 수) 카운터, 로그에 주기 출력 |

레플리카 다중화 시 Redis `BF.ADD`/`BF.EXISTS`로 교체 가능하다. 현재는 `@DistributedLock`으로 단일 인스턴스 보장.

---

## 데이터 일관성 패턴

### Saga — 주문 처리 오케스트레이터

분산 트랜잭션 없이 보상 트랜잭션(Compensation)으로 일관성을 보장한다.

#### 단계 (정방향)

```
INIT → VALIDATED → CASH_RESERVED → ORDER_CREATED → ORDER_FILLED → CASH_SETTLED → COMPLETED
```

#### 보상 단계 (실패 시 역순)

| 실패 단계 | 보상 액션 |
|-----------|-----------|
| ORDER_FILLED 이후 | 미체결 주문 취소 (OrderBook에서 제거) |
| ORDER_CREATED | 주문 상태 CANCELLED 처리 |
| CASH_RESERVED | `reserved_amount` 환불 (cash + reserved_amount) |

#### 사가 상태

```
STARTED → COMPLETED
        ↘ COMPENSATING → COMPENSATED
                        ↘ FAILED  (보상도 실패 — 수동 검토)
```

#### 영속성

`order_sagas` 테이블에 `(id, user_id, current_step, status, reserved_amount, started_at)` 기록.
기동 후 5분 이상 STARTED/COMPENSATING 상태인 사가를 `recoverIncomplete()` 스케줄러(5분 주기)가 자동 보상 재시도.

---

### CQRS 읽기모델 — portfolio_positions

`paper_trades` 집계를 요청마다 재계산하는 대신, 사전에 집계된 읽기 전용 테이블을 유지한다.

#### 쓰기 측 (Write Model)

- `PaperTradingService.buy()` → `PortfolioPositionProjection.onBuy()` — net_qty 증가, avg_buy_price/total_cost 갱신
- `PaperTradingService.sell()` → `PortfolioPositionProjection.onSell()` — net_qty 감소, 0이 되면 행 삭제
- `PaperTradingService.reset()` → `PortfolioPositionProjection.onReset()` — 해당 유저 포지션 전체 삭제

#### 읽기 측 (Read Model)

| 기존 쿼리 | 읽기모델 쿼리 |
|-----------|---------------|
| `paper_trades` 전체 집계 (`GROUP BY`, `HAVING`) | `portfolio_positions WHERE user_id = ?` |
| N개 종목 × 개별 최신가 조회 | `LATERAL JOIN candles_1m` 단일 쿼리 |

- `PaperPortfolioQueryService.buildHoldings()` — `portfolio_positions` 직접 SELECT
- `WalletService.calcHoldingsValue()` — `portfolio_positions + LATERAL JOIN candles_1m` 단일 쿼리

#### 테이블 구조

```sql
portfolio_positions (
    user_id       BIGINT,
    stock_id      BIGINT,
    net_qty       INTEGER,       -- 보유 수량
    avg_buy_price NUMERIC(18,4), -- 평균 매수가
    total_cost    NUMERIC(18,4), -- 총 매수 금액 (avg_buy_price 갱신에 사용)
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (user_id, stock_id)
)
```

---

## Architecture Decision Records (ADRs)

| ADR | 제목 | 상태 |
|-----|------|------|
| [ADR-001](decisions/001-modular-monolith.md) | Start with Modular Monolith, not Microservices | Accepted |
| [ADR-002](decisions/002-timescaledb.md) | Use TimescaleDB for Time-Series Market Data | Accepted |
| [ADR-003](decisions/003-stock-events-central.md) | Centralize Stock Event Detection in Worker | Accepted |
| [ADR-004](decisions/004-redis-streams-over-kafka.md) | Redis Streams over Kafka for MVP | Superseded by ADR-005 |
| [ADR-005](decisions/005-kafka-go-gateway-netty-broadcast.md) | Introduce Kafka, Go Ingestion Gateway, Netty Broadcast | Accepted (Netty Broadcast portion superseded by ADR-033; Kafka/Go remain) |
| [ADR-006](decisions/006-kafka-dlt-retry-strategy.md) | @RetryableTopic + Dead Letter Topic for Kafka Fault Isolation | Accepted |
| [ADR-007](decisions/007-idempotency-key-filter.md) | Idempotency Key Filter for Mutating Order Endpoints | Accepted |
| [ADR-008](decisions/008-outbox-pattern-spring-modulith.md) | Outbox Pattern via Spring Modulith Events Kafka | Accepted |
| [ADR-009](decisions/009-kubernetes-service-discovery-nginx-gateway.md) | K8s DNS as Service Discovery + NGINX Ingress as API Gateway | Accepted |
| [ADR-010](decisions/010-bloom-filter-news-deduplication.md) | Guava Bloom Filter for News URL Deduplication | Accepted |
| [ADR-011](decisions/011-order-saga-orchestration.md) | Orchestration-based Saga for Order Processing | Accepted |
| [ADR-012](decisions/012-cqrs-portfolio-positions-read-model.md) | CQRS Read Model Table for Portfolio Positions | Accepted |
| [ADR-013](decisions/013-append-only-ledger-wallet.md) | Append-Only Ledger for Wallet | Accepted |
| [ADR-014](decisions/014-t2-paper-settlement-scheduler.md) | T+2 Business Day Settlement for Paper Trading | Accepted |
| [ADR-015](decisions/015-conditional-mock-real-client.md) | @ConditionalOnProperty for Mock/Real Client Switching | Accepted |
| [ADR-016](decisions/016-subscription-creator-revenue-sharing.md) | Subscription Plan and Creator Revenue Sharing Model | Accepted |
| [ADR-017](decisions/017-investor-flow-kis-integration.md) | 개인·외국인·기관 순매수(투자자 동향) 데이터 — KIS API 확장 | Accepted |
| [ADR-018](decisions/018-stock-fundamentals-kis-reuse.md) | 시가총액·PER·PBR 스크리너 필터 — KIS 응답 필드 재사용 + 스냅샷 테이블 | Accepted |
| [ADR-019](decisions/019-spring-modulith-boundary-conventions.md) | Spring Modulith 모듈 경계 규칙 확립 | Accepted |
| [ADR-020](decisions/020-stock-valuation-score.md) | 종목 스코어(Snowflake 참고) v1 — 밸류에이션 1축만 실데이터 | Accepted |
| [ADR-021](decisions/021-candles-1d-realtime-upsert.md) | candles_1d 무기록 버그 — CandleAggregator 실시간 upsert로 해결 | Accepted |
| [ADR-022](decisions/022-tick-consumer-msa-role-gating.md) | msa 프로필 3중 market.ticks 중복 소비 제거 | Accepted |
| [ADR-023](decisions/023-commercialization-pivot.md) | MVP 졸업 — 상용 서비스 전환 (BYOK 브로커 연동, 실시세, AI 가드레일) | Accepted |
| [ADR-033](decisions/033-remove-netty-broadcast-gateway.md) | Remove Netty Broadcast Gateway (never had a frontend client) | Accepted |
| [ADR-038](decisions/038-broadcast-consumer-partition-assignment.md) | 시세 브로드캐스트 컨슈머 — 전 파티션 수동 할당 + conflation | Accepted |
| [ADR-039](decisions/039-drop-global-market-topic.md) | `/topic/market` 폐지 — 시장 요약 1Hz + 가시 종목 구독 | Accepted |
| [ADR-040](decisions/040-kafka-topic-declaration.md) | Kafka 토픽 코드 선언 · auto-create 폐지 · 파티션 설계 | Accepted |
| [ADR-041](decisions/041-timescale-hypertable-promotion.md) | TimescaleDB hypertable 승격·압축, 원시 틱 미저장 확정 | Accepted |
| [ADR-042](decisions/042-outbox-based-es-indexing.md) | ES 인덱싱 Outbox 단일 파이프라인 통일 (CDC 미채택) | Accepted |
| [ADR-043](decisions/043-ledger-pagination-and-reconciliation.md) | 원장 커서 페이징 + 대사 스냅샷 (ADR-013 서술 정정) | Accepted |
| [ADR-044](decisions/044-alert-rule-in-memory-index.md) | 알림 룰 인메모리 인덱스 · 평가/발송 분리 | Accepted |
| [ADR-045](decisions/045-performance-slo-and-verification-harness.md) | 워크로드별 SLO 정의 + 검증 하네스 범위 | Accepted |
| [ADR-046](decisions/046-detector-state-in-memory.md) | 감지기 EMA 상태 Redis → 메모리 — 틱당 Redis 왕복 제거 | Accepted |
| [ADR-047](decisions/047-single-execution-path-for-paper-account.md) | 모의투자 계좌의 체결 경로를 매칭 엔진 하나로 통일 (paper는 계좌 기록 모듈) | Accepted |
| [ADR-048](decisions/048-retire-trading-service.md) | trading-service 폐기 — api가 한 번도 위임한 적 없는 복사본 | Accepted |
| [ADR-049](decisions/049-retire-quant-engine.md) | quant-engine 폐기 — 위임 미연결, L-06 실측으로 bulkhead 격리 충분 확인 | Accepted |
| [ADR-050](decisions/050-realtime-pipeline-defaults-from-load-tests.md)–[ADR-054](decisions/054-pinpoint-apm-alongside-jaeger.md) | 부하테스트 기반 기본값 · 이벤트 트리거 모의주문 · 현금 예약 락 · 결제 멱등성 · Pinpoint | Accepted |
| [ADR-055](decisions/055-price-provenance-gate-for-real-orders.md) | 실주문은 출처가 확인된 실시세로만 발동 — 틱 출처 태깅 | Accepted |
| [ADR-056](decisions/056-brokerage-order-unknown-outcome.md) | 실거래 주문 결과 불명을 일급 상태로 — 의도 선기록, 실패 3분류, 당일 목록 대조 | Accepted |
| [ADR-057](decisions/057-real-order-kill-switch.md) | 실거래 주문 킬 스위치 — Postgres 플래그, 전역·증권사·사용자, 주문 준비 트랜잭션에서 판정 | Accepted |
| [ADR-058](decisions/058-risk-gate-in-flight-exposure.md) | 실거래 리스크 게이트 — 진행 중 매수를 노출에, 집중도 분모는 증권사 총평가액 | Accepted |
| [ADR-060](decisions/060-realtime-price-coverage-for-conditional-orders.md) | 조건부 주문은 실시세가 흐르는 종목에만 — worker 공표, 생성 시 거부, 상실 감시, Mock 증권사 예외 | Accepted |
| [ADR-061](decisions/061-brokerage-order-status-sync-and-single-settlement.md) | 접수된 실거래 주문 주기 동기화, 모든 체결 경로 행 락, 체결 하나에 정산 하나 | Accepted |
| [ADR-062](decisions/062-real-account-daily-loss-realized-pnl.md) | 실거래 일간 손실은 실현손익 — 매도 시점 증권사 평단가 기록 | Accepted |
