# ADR-004: Use Redis Streams for MVP, Kafka Later

## Status
Superseded by [ADR-005](005-kafka-go-gateway-netty-broadcast.md)

**Note (2026-10-04):** Redis Streams 버스는 계획만 됐고 구현된 적이 없다. 실시간 틱·이벤트 버스는 ADR-005로
Kafka가 됐다(ADR-005 Status도 이 결정을 대체한다고 적고 있었으나 이 파일의 Status가 갱신되지 않았다 —
[2026-10 설계 리뷰](../design-review-2026-10.md)에서 발견).

## Context

The realtime data pipeline requires a message bus between the Market Data Collector and downstream consumers (Candle Aggregator, Event Detector, WebSocket Broadcaster).

Options considered: Redis Pub/Sub, Redis Streams, Kafka.

## Decision

Use **Redis Streams** for the MVP pipeline. Migrate to Kafka when throughput or reliability demands it.

## Reasons

- Redis is already in the stack for price caching. No new infrastructure.
- Redis Streams supports consumer groups and message acknowledgment — sufficient for MVP reliability.
- Kafka adds significant operational overhead (broker, ZooKeeper/KRaft, topic management) that is not justified at low volume.
- Redis Streams → Kafka migration path is straightforward: same producer/consumer interface, different underlying client.

## Consequences

- Redis becomes a single point of failure for both caching and streaming. Acceptable at MVP scale.
- Redis Streams do not support long-term message replay or complex stream processing. Event history is stored in `stock_events`, not in the stream.
- Consumer group names and stream keys must be documented (see `data-model.md` Redis section).

## Revisit When

- Tick throughput exceeds Redis single-thread write capacity.
- Need replay for backfill or audit purposes.
- Multiple independent consumer applications need to subscribe to the same stream with different processing logic.
