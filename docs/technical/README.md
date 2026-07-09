# Technical Documentation

monticker의 핵심 기술 구현에 대한 심층 기술 문서입니다.

## 데이터 파이프라인

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [ema-event-detection.md](./ema-event-detection.md) | EMA 기반 이상 탐지 | Kotlin, EMA, 적응형 임계값 |
| [timescaledb-candle-pipeline.md](./timescaledb-candle-pipeline.md) | 캔들 데이터 파이프라인 | TimescaleDB, Hypertable, CAgg |
| [latency-tradeoff.md](./latency-tradeoff.md) | 시세 파이프라인 지연-정확성 트레이드오프 | WebSocket, REST, STOMP |

## 실시간 시스템

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [realtime-price-architecture.md](./realtime-price-architecture.md) | 실시간 가격 아키텍처 | STOMP, WebSocket, React |
| [request-deduplication.md](./request-deduplication.md) | TanStack Query Single Flight | React Query, 요청 중복 제거 |
| [kafka-tick-pipeline.md](./kafka-tick-pipeline.md) | Kafka 기반 시세 파이프라인 전체 흐름 | Kafka, Go, Netty, goroutine, EventLoopGroup |
| [go-market-gateway.md](./go-market-gateway.md) | Go 시세 수집 게이트웨이 구현 상세 | goroutine-per-stock, pgx, kafka-go, 랜덤워크 |
| [netty-broadcast-gateway.md](./netty-broadcast-gateway.md) | Netty WebSocket 브로드캐스트 게이트웨이 | NioEventLoopGroup, 구독 라우팅, KafkaBridge |

## 보안 및 인증

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [jwt-authentication.md](./jwt-authentication.md) | JWT 기반 무상태 인증 | Spring Security, JJWT, Refresh Rotation |

## 금융 도메인

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [backtesting-engine.md](./backtesting-engine.md) | 백테스팅 엔진 설계 | 전략 패턴, Sharpe/MDD/PF |
| [portfolio-risk-metrics.md](./portfolio-risk-metrics.md) | 포트폴리오 리스크 지표 | Sharpe, Beta, VaR, 금융 수학 |
| [paper-trading.md](./paper-trading.md) | 모의투자 시스템 설계 | TanStack Query Mutation, UX 설계 |
| [matching-engine-clob.md](./matching-engine-clob.md) | 체결 엔진 — CLOB 기반 주문 매칭 | TreeMap, 가격/시간 우선, 슬리피지 |
| [risk-limit-system.md](./risk-limit-system.md) | 리스크 한도 시스템 — 주문 전 동기 게이트 | VaR, 동시성, 감사 로그 |
| [quant-rule-engine.md](./quant-rule-engine.md) | Quant Lab 룰 엔진 — 조건식 평가 | RSI/MACD/Bollinger, DSL 평가, 신뢰도 점수 |
| [quant-analytics-algorithms.md](./quant-analytics-algorithms.md) | 포트폴리오 최적화·패턴 인식·국면 분류 | Gradient Descent, ZigZag, ADX |
| [event-sourcing-ledger.md](./event-sourcing-ledger.md) | Investment Wallet 이벤트 소싱 원장 | 이벤트 소싱, 잔고 재구성 |
| [backend-test-strategy.md](./backend-test-strategy.md) | 백엔드 테스트 전략 | MockK, JdbcTemplate 목킹, 311 tests |

## 성능 최적화

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [worker-performance.md](./worker-performance.md) | Worker 성능 분석 및 병목 해결 | HikariCP, Thread Pool, 비동기 |
| [screener-virtualization.md](./screener-virtualization.md) | 스크리너 목록 가상화 | TanStack Virtual, DOM 최적화 |
| [chart-adapter-pattern.md](./chart-adapter-pattern.md) | 차트 라이브러리 어댑터 패턴 | Apache ECharts, 의존성 역전 |

## 아키텍처 패턴

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [eda-event-driven-architecture.md](./eda-event-driven-architecture.md) | EDA 적용 사례 전체 — 도입 배경·설계·트레이드오프 | Spring ApplicationEvent, Modulith, @TransactionalEventListener |
| [outbox-pattern.md](./outbox-pattern.md) | Outbox Pattern — Spring Modulith Events Kafka | @Externalized, event_publication, at-least-once |
| [order-saga.md](./order-saga.md) | Order Saga — 주문 처리 분산 트랜잭션 | 보상 트랜잭션, 복구 스케줄러, REQUIRES_NEW |
| [ddd-domain-driven-design.md](./ddd-domain-driven-design.md) | DDD 적용 검토 — Anemic Model 진단·Rich Model 전환 로드맵 | 상태 전이 메서드, Aggregate Root, Value Object |
| [cqrs-portfolio-positions.md](./cqrs-portfolio-positions.md) | CQRS 읽기모델 — portfolio_positions | 동기 프로젝션, LATERAL JOIN, N+1 제거 |

## 신뢰성 및 관측가능성

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [resilience-patterns.md](./resilience-patterns.md) | 신뢰성 패턴 모음 — DLT·Idempotency·Bulkhead·DistributedLock·RequestID | @RetryableTopic, Redis SETNX, MDC, Graceful Shutdown |
| [bloom-filter-deduplication.md](./bloom-filter-deduplication.md) | Bloom Filter — 뉴스 URL 중복 제거 | Guava BloomFilter, 2M/1%FPP, @PostConstruct 시드 |
| [circuit-breaker.md](./circuit-breaker.md) | Circuit Breaker 장애 격리 | Resilience4j, 상태 머신 |
| [opentelemetry-tracing.md](./opentelemetry-tracing.md) | OpenTelemetry 분산 추적 | Jaeger, Micrometer, OTLP |
| [benchmark-debugging.md](./benchmark-debugging.md) | API 벤치마크 디버깅 포스트모템 | k6, Rate Limiter, SQL Alias |

## 관련 문서

- [Architecture Overview](../architecture.md) — 전체 시스템 아키텍처
- [Data Model](../data-model.md) — DB 스키마
- [ADRs](../decisions/) — 아키텍처 결정 기록
| [market-hours-vwap.md](./market-hours-vwap.md) | 장 시간 처리와 VWAP | MarketSchedule, VWAP, 지연 측정 |
