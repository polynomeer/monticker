# monticker

> 이벤트 중심 주식 관찰 앱 — 가격 급등·거래량 급증을 실시간으로 감지하고 모바일 알림으로 전달합니다.

[![backend-ci](https://github.com/augboot/monticker/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/augboot/monticker/actions/workflows/backend-ci.yml)
[![web-ci](https://github.com/augboot/monticker/actions/workflows/web-ci.yml/badge.svg)](https://github.com/augboot/monticker/actions/workflows/web-ci.yml)

## Overview

monticker는 **stock_events**를 중심 도메인으로 두는 이벤트 중심 설계를 채택합니다.
가격 틱·거래량 데이터를 실시간으로 수집하고, EMA 기반 이상 탐지기가 이벤트를 생성하며,
웹·모바일 클라이언트가 이벤트 타임라인과 차트를 통해 이를 시각화합니다.

```
┌─────────────┐   WebSocket/REST   ┌─────────────────┐
│  Next.js 15 │ ◄────────────────► │  Spring Boot API │
│  (apps/web) │                    │  (backend/api)   │
└─────────────┘                    └────────┬─────────┘
                                            │ JPA / JDBC
┌─────────────┐   Redis Streams    ┌────────▼─────────┐
│  Expo Mobile│ ◄── push ──────── │   TimescaleDB     │
│(apps/mobile)│                    │  (PostgreSQL 16)  │
└─────────────┘                    └────────▲─────────┘
                                            │ JDBC
                                   ┌────────┴─────────┐
                                   │  Spring Worker   │
                                   │ (backend/worker) │
                                   └──────────────────┘
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend API | Kotlin 2.0 + Spring Boot 3.5 + JPA |
| Worker | Kotlin + Spring Scheduling |
| Database | TimescaleDB (PostgreSQL 16) + Flyway |
| Cache | Redis 7 |
| Web | Next.js 15 (App Router) + Tailwind CSS |
| Mobile | Expo 52 (React Native) |
| Chart | Lightweight Charts 4 |
| Realtime | WebSocket (STOMP over SockJS) |
| CI | GitHub Actions |

## Quick Start

### Prerequisites

- Docker & Docker Compose
- JDK 21
- Node.js 20 + pnpm 9

### 1. Infrastructure

```bash
docker compose up -d timescaledb redis
```

### 2. Backend API

```bash
cd backend/api
./gradlew bootRun
# http://localhost:8080
```

### 3. Worker

```bash
cd backend/worker
./gradlew bootRun
```

### 4. Web

```bash
cd apps/web
pnpm install
pnpm dev
# http://localhost:3000
```

### 5. Full stack (Docker)

```bash
docker compose --profile full up
```

## Key Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/stocks/search?query=` | 종목 검색 |
| GET | `/api/stocks/{id}/price` | 현재가 |
| GET | `/api/stocks/{id}/candles?interval=1d` | 캔들 데이터 |
| GET | `/api/stocks/{id}/events` | 이벤트 타임라인 |
| GET | `/api/watchlists` | 관심종목 그룹 |
| POST | `/api/alerts/rules` | 알림 규칙 생성 |
| WS | `/ws` (STOMP) | 실시간 가격·이벤트 |

## Project Structure

```
monticker/
├── apps/
│   ├── web/          # Next.js 15
│   └── mobile/       # Expo 52
├── backend/
│   ├── api/          # Spring Boot REST + WebSocket
│   └── worker/       # Market data collector + Event detector
├── packages/
│   └── types/        # Shared TypeScript types
├── infra/
│   └── docker/       # Nginx, TimescaleDB init
└── docs/             # Architecture, product, decisions
```

## Event Detection

Worker는 1초마다 가격 틱을 수집하고 EMA(α=0.1) 기반으로 이상을 탐지합니다.

| Event Type | Trigger |
|------------|---------|
| PRICE_SPIKE | EMA 대비 가격 변화율 ≥ 임계값 |
| PRICE_DROP | EMA 대비 가격 하락 ≥ 임계값 |
| VOLUME_SURGE | EMA 거래량 대비 현재 거래량 ≥ 3× |

중복 이벤트는 `(stock_id, event_type, date_trunc('minute', event_time))` 유니크 인덱스로 방지합니다.

## License

MIT © augboot
