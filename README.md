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

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_URL` | ✅ | `jdbc:postgresql://localhost:5432/monticker` | PostgreSQL 접속 URL |
| `DB_USER` | ✅ | `monticker` | DB 사용자명 |
| `DB_PASSWORD` | ✅ | `monticker` | DB 패스워드 |
| `REDIS_HOST` | ✅ | `localhost` | Redis 호스트 |
| `REDIS_PORT` | | `6379` | Redis 포트 |
| `ELASTICSEARCH_URI` | ✅ | `http://localhost:9200` | Elasticsearch URI |
| `JWT_SECRET` | ✅ | (개발용 기본값) | JWT 서명 키 (32바이트 이상) |
| `ANTHROPIC_API_KEY` | ✅ | — | Claude AI 요약 API 키 |
| `NAVER_CLIENT_ID` | | — | 네이버 뉴스 API Client ID |
| `NAVER_CLIENT_SECRET` | | — | 네이버 뉴스 API Secret |
| `DART_API_KEY` | | — | DART 공시 API 키 |
| `KIS_APP_KEY` | | — | 한국투자증권 APP KEY |
| `KIS_APP_SECRET` | | — | 한국투자증권 APP SECRET |
| `MAIL_HOST` | | `smtp.gmail.com` | SMTP 서버 호스트 |
| `MAIL_PORT` | | `587` | SMTP 포트 |
| `MAIL_USERNAME` | | — | SMTP 계정 |
| `MAIL_PASSWORD` | | — | SMTP 패스워드 |
| `MAIL_FROM` | | `noreply@monticker.io` | 발신 이메일 주소 |
| `APP_BASE_URL` | | `http://localhost:3000` | 웹 앱 기본 URL (이메일 링크용) |
| `ALLOWED_ORIGINS` | | `http://localhost:3000` | CORS 허용 Origin (쉼표 구분) |
| `NEXT_PUBLIC_API_URL` | | `http://localhost:8080` | 웹 → API 서버 URL |
| `NEXT_PUBLIC_BASE_URL` | | `https://monticker.io` | 웹 앱 공개 URL (OG 태그용) |
| `GOOGLE_CLIENT_ID` | | — | Google OAuth2 Client ID |
| `GOOGLE_CLIENT_SECRET` | | — | Google OAuth2 Client Secret |
| `KAKAO_CLIENT_ID` | | — | 카카오 앱 REST API 키 |
| `KAKAO_CLIENT_SECRET` | | — | 카카오 Client Secret (선택) |
| `NAVER_CLIENT_ID` | | — | 네이버 애플리케이션 Client ID |
| `NAVER_CLIENT_SECRET` | | — | 네이버 애플리케이션 Client Secret |

### 이메일(인증/비밀번호 재설정) 로컬 테스트

`MAIL_USERNAME`/`MAIL_PASSWORD` 없이 로컬에서 `./gradlew bootRun`으로 API를 띄우면
실제 이메일이 발송되지 않고 실패가 조용히 로그로만 남습니다 — 발송 자체를 확인할 방법이 없습니다.

`docker-compose.yml`에 로컬 전용 SMTP 캐처([MailHog](https://github.com/mailhog/MailHog))가 포함되어 있습니다:

```bash
docker compose up -d mailhog
MAIL_HOST=localhost MAIL_PORT=1025 ./gradlew bootRun   # backend/api 디렉터리에서
```

이메일 인증/비밀번호 재설정을 요청하면 실제 발송 없이 http://localhost:8025 에서
그대로 확인할 수 있습니다. `docker compose --profile full` 로 `api` 컨테이너까지 띄우는
경우엔 이미 `MAIL_HOST=mailhog`가 기본값으로 연결되어 있어 별도 설정이 필요 없습니다.

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
