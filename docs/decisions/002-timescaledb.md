# ADR-002: Use TimescaleDB for Price and Candle Data

## Status
Accepted

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
