# ADR-001: Start with Modular Monolith, not Microservices

## Status
Accepted

## Context

monticker requires multiple functional domains: price collection, event detection, news, disclosures, alerts, portfolio, simulation, and AI insight. The question is whether to split these into microservices from the start.

## Decision

Start as a **single deployable Spring Boot application** with clear internal module boundaries. Run async work in a separate worker application, but keep it one deployable unit.

## Reasons

- Team size is 1–2 people. Microservices multiply operational burden without adding capacity.
- Module boundaries can be enforced by package structure and interface separation, without network overhead.
- Splitting too early locks in wrong service boundaries before the domain is understood.
- A Modular Monolith can be extracted into services later when a specific module needs independent scaling.

## Consequences

- All modules share a single database connection pool and transaction boundary.
- Must be disciplined about not letting modules call each other's repositories directly.
- Future extraction requires refactoring, but the cost is known and manageable.

## Revisit When

A single module (e.g., WebSocket broadcaster or Event Detector) consistently saturates resources while others are idle.
