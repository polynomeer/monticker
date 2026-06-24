# Technical Documentation

이 디렉토리는 monticker의 핵심 기술 구현에 대한 심층 기술 문서를 포함합니다.

## 문서 목록

| 문서 | 주제 | 핵심 기술 |
|------|------|-----------|
| [ema-event-detection.md](./ema-event-detection.md) | EMA 기반 이상 탐지 시스템 | Kotlin, EMA, TimescaleDB |
| [timescaledb-candle-pipeline.md](./timescaledb-candle-pipeline.md) | 캔들 데이터 파이프라인 | TimescaleDB, LATERAL JOIN, CAgg |
| [chart-adapter-pattern.md](./chart-adapter-pattern.md) | 차트 라이브러리 어댑터 패턴 | TypeScript, Apache ECharts, Next.js |
| [realtime-price-architecture.md](./realtime-price-architecture.md) | 실시간 가격 아키텍처 | STOMP, WebSocket, React |
| [worker-performance.md](./worker-performance.md) | Worker 성능 분석 및 병목 해결 | Kotlin, HikariCP, Thread Pool |
| [benchmark-debugging.md](./benchmark-debugging.md) | API 벤치마크 디버깅 포스트모템 | k6, Spring Security, PostgreSQL |

## 관련 문서

- [Architecture Overview](../architecture.md) — 전체 시스템 아키텍처
- [Data Model](../data-model.md) — DB 스키마 전체
- [ADRs](../decisions/) — 아키텍처 결정 기록
