# ADR-005: Introduce Kafka, a Go Ingestion Gateway, and a Netty Broadcast Gateway

## Status
Accepted (supersedes the "stay on Redis Streams" stance of ADR-004 for the realtime tick/event bus)

## Context

ADR-004 chose Redis Streams over Kafka for the MVP, reasoning that tick volume was low and Kafka's operational overhead wasn't justified. That reasoning still holds **for the application's actual traffic** — this project does not have production-scale tick volume.

The motivation for this ADR is different: an explicit decision to use this project to demonstrate the technology stack used by exchange/brokerage market-data teams (Toss Securities 시세팀 and similar), specifically the parts of that stack monticker had not yet touched — **Kafka, Go, and Netty**. The existing stack already covers Kotlin/Spring Boot and Redis.

This is a portfolio/learning-driven architecture change, not a load-driven one. That distinction is stated explicitly so the trade-offs below are read in the right context.

## Decision

1. **Kafka** replaces the (planned but never implemented) Redis Streams tick/event bus as the backbone between tick ingestion and downstream consumers.
2. **A new Go service (`services/market-gateway`)** owns tick generation/ingestion and is the sole producer to Kafka's `market.ticks` topic. It replaces `MockPriceGenerator`'s role as the tick source for the Worker.
3. **A new Netty-based broadcast gateway (`services/broadcast-gateway`)** consumes `market.ticks` and `market.events` from Kafka and pushes to WebSocket clients directly, bypassing Spring's STOMP message broker. It replaces the previously unused `PriceBroadcaster` (`backend/api/.../marketdata/infrastructure/PriceBroadcaster.kt`), which was wired up but never actually invoked by any scheduler — broadcast was a dead code path before this change.

```
Go Market Gateway (tick generation / KIS ingestion in future)
  → Kafka topic: market.ticks
       ├─→ Kotlin Worker (Kafka consumer)
       │     → RedisTickWriter, CandleAggregator, EventDetector
       │     → Kafka topic: market.events (on event detection)
       └─→ Netty Broadcast Gateway (Kafka consumer)
             → WebSocket clients (ws://localhost:9090/ws)

Kafka topic: market.events
       └─→ Netty Broadcast Gateway (Kafka consumer)
             → WebSocket clients
```

## Reasons

- **Kafka**: A durable, replayable log decouples tick ingestion from however many consumers need it (Worker for persistence/detection, broadcast gateway for realtime push, future consumers like the Quant Lab signal engine). Redis Streams could do this too, but Kafka is the tool actually used at the scale and by the teams this project wants to demonstrate familiarity with.
- **Go**: Goroutines handle many concurrent outbound connections (to KIS or other tick sources) more cheaply than JVM threads. Separating ingestion into its own small, fast, independently-deployable service mirrors how real market-data pipelines isolate the "hot path" of receiving exchange data from the JVM business-logic services that process it.
- **Netty**: Direct event-loop based WebSocket handling avoids the overhead of Spring's STOMP broker (`SimpleBrokerMessageHandler`, full Spring MVC dispatch per message) for the single hottest, most latency-sensitive path in the system — pushing price updates to open client connections.

## Consequences

- **More moving parts**: two new deployable services, one new infrastructure dependency (Kafka). For a 1–2 person team this is a real operational cost; ADR-001's "avoid unnecessary microservices" principle is knowingly relaxed here for the two services whose entire reason to exist is demonstrating non-JVM/non-Spring tech.
- **Two ingestion paths temporarily coexist.** The Worker's existing `MockPriceGenerator` path (in-process, no Kafka) is left in place behind a feature flag (`ingestion.source=internal|kafka`) rather than deleted outright, so the system keeps working if Kafka is down or not deployed (e.g., local dev without `docker compose --profile kafka up`).
- **The previously-dead `PriceBroadcaster`/STOMP path is not deleted**, only superseded as the *default* broadcast mechanism. The frontend can still connect to the STOMP endpoint; the Netty gateway is an additional, recommended endpoint.
- **Local dev complexity increases.** Kafka (KRaft mode, no ZooKeeper) is added to `docker-compose.yml` behind a `kafka` profile so the default `dev.sh` flow is unaffected unless explicitly enabled.
- **Go and Netty/Kotlin codebases now both need to agree on the wire format** for `market.ticks` and `market.events` (JSON, documented in `docs/technical/kafka-tick-pipeline.md`). A schema registry (e.g., Avro + Confluent Schema Registry) would be the production-grade answer; using plain JSON here is a deliberate simplification given the scope.

## Revisit When

- The two new services add more operational burden than learning/demonstration value (e.g., if this becomes a maintenance drag rather than a portfolio asset).
- A schema registry becomes necessary because multiple independently-deployed consumers start disagreeing on tick shape.
- KIS real-market ingestion is moved into the Go gateway (currently it generates mock ticks; wiring real KIS WebSocket data into Go is a natural next step but out of scope for this ADR).
