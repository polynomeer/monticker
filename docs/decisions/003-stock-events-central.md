# ADR-003: stock_events as the Central Domain Table

## Status
Accepted

## Context

monticker aggregates price movements, news, disclosures, volume anomalies, and sentiment signals to display a unified chart timeline. The question is how to model this in the database.

Two options:
1. Query each source table separately and merge in the application layer.
2. Write all events to a single `stock_events` table and serve the timeline from there.

## Decision

All significant market events — regardless of source — are normalized and written to **`stock_events`** at ingestion time. The chart timeline API reads only from `stock_events`.

## Reasons

- A single table makes the timeline query trivial: `WHERE stock_id = ? AND event_time BETWEEN ? AND ? ORDER BY event_time`.
- Importance scoring, sentiment scoring, and deduplication are applied once at write time, not at read time.
- New event sources (e.g., regulatory filings, earnings calls) can be added without changing the timeline API.
- The application layer does not need to merge heterogeneous result sets at query time.

## Consequences

- Every collector (news, disclosure, price detector) is responsible for writing to `stock_events` as part of its job.
- `source_type` + `source_id` in `stock_events` maintains the link back to the original record.
- `metadata_json` provides a flexible extension point for source-specific data without schema changes.
- Deduplication must be enforced at write time (unique constraint or idempotency check on `stock_id + event_type + event_time bucket`).

## Revisit When

Event volume grows to the point where a single `stock_events` table becomes a write bottleneck. At that point, consider partitioning by `stock_id` range or time, or separating high-frequency system events from low-frequency user events.
