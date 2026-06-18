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
