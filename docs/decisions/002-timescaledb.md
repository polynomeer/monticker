# ADR-002: Use TimescaleDB for Price and Candle Data

## Status
Accepted

**Note (2026-09-10):** 이 ADR의 Consequences 3번("Flyway migrations must call `create_hypertable()`")은 작성 이후 한 번도 이행되지 않았다 — 전환 스크립트가 어떤 실행 경로에도 연결되지 않아 대상 테이블은 계속 일반 PostgreSQL 테이블로 남아 있었다. [ADR-041](041-timescale-hypertable-promotion.md)이 이를 Flyway로 이행하고, 동시에 `price_ticks`(한 번도 쓰인 적 없음)와 Continuous Aggregate는 채택하지 않기로 결정한다. 나머지 근거는 유효하므로 이 ADR은 Accepted로 유지된다.

## Context

Price ticks and candle data are written continuously at high frequency and queried by time range. A standard PostgreSQL table degrades in performance as row count grows into hundreds of millions.

## Decision

Use **TimescaleDB** (PostgreSQL extension) for `price_ticks`, `candles_1m`, `candles_5m`, `candles_1d`.

## Reasons

- TimescaleDB hypertables automatically partition data by time, keeping query performance stable as data grows.
- It is a PostgreSQL extension — same connection, same ORM, same migration tooling (Flyway). No separate infrastructure.
- Built-in time-series functions (`time_bucket`, continuous aggregates) simplify candle aggregation queries.
- Avoids introducing a separate time-series database (InfluxDB, QuestDB) that would require a different client and ops model.

## Consequences

- PostgreSQL instance must have the TimescaleDB extension installed.
- Docker Compose must use `timescale/timescaledb` image instead of plain `postgres`.
- Flyway migrations must call `create_hypertable()` after table creation.

## Revisit When

Write throughput exceeds what a single TimescaleDB node can handle, at which point TimescaleDB Cloud or a dedicated time-series store becomes worth the operational cost.
