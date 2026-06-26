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

## 성능 최적화

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [worker-performance.md](./worker-performance.md) | Worker 성능 분석 및 병목 해결 | HikariCP, Thread Pool, 비동기 |
| [screener-virtualization.md](./screener-virtualization.md) | 스크리너 목록 가상화 | TanStack Virtual, DOM 최적화 |
| [chart-adapter-pattern.md](./chart-adapter-pattern.md) | 차트 라이브러리 어댑터 패턴 | Apache ECharts, 의존성 역전 |

## 신뢰성 및 관측가능성

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [circuit-breaker.md](./circuit-breaker.md) | Circuit Breaker 장애 격리 | Resilience4j, 상태 머신 |
| [opentelemetry-tracing.md](./opentelemetry-tracing.md) | OpenTelemetry 분산 추적 | Jaeger, Micrometer, OTLP |
| [benchmark-debugging.md](./benchmark-debugging.md) | API 벤치마크 디버깅 포스트모템 | k6, Rate Limiter, SQL Alias |

## 관련 문서

- [Architecture Overview](../architecture.md) — 전체 시스템 아키텍처
- [Data Model](../data-model.md) — DB 스키마
- [ADRs](../decisions/) — 아키텍처 결정 기록
