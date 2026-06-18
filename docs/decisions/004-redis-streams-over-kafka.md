# ADR-004: Use Redis Streams for MVP, Kafka Later

## Status
Accepted

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
