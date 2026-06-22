# monticker

Event-centric stock observation app.

> Show *why* a price moved — by overlaying news, disclosures, volume anomalies, and sentiment signals on the chart timeline.

## Quick Start

```bash
# Start infrastructure (PostgreSQL + Redis)
make up

# Run API (separate terminal)
make api-run

# Run Web (separate terminal)
make web-install
make web-dev
```

## Structure

```
apps/web        Next.js web client
backend/api     Spring Boot API (Kotlin)
backend/worker  Spring Boot async workers (Kotlin)
packages/types  Shared TypeScript types
infra/          Docker, Nginx
docs/           Product, architecture, decisions
```

## Docs

- [Product](docs/product.md)
- [Architecture](docs/architecture.md)
- [Workflow](docs/workflow.md)
- [Data Model](docs/data-model.md)
- [External APIs](docs/external-apis.md)

## TimescaleDB Continuous Aggregates

Worker가 `price_ticks` hypertable에 1초마다 틱을 기록하면, TimescaleDB가 자동으로
`candles_1m_cagg` (1분 간격) 와 `candles_1d_cagg` (일별) 뷰를 집계합니다.

| View | Interval | Refresh |
|------|----------|---------|
| `candles_1m_cagg` | 1분 | 매 1분 |
| `candles_1d_cagg` | 1일 | 매 1시간 |

API의 `GET /api/stocks/{id}/candles`는 CAgg view를 우선 사용하고,
없으면 `CandleAggregator`가 직접 채운 `candles_1m` 테이블로 fallback합니다.
