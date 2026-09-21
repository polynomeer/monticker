# monticker

[![backend-ci](https://github.com/polynomeer/monticker/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/polynomeer/monticker/actions/workflows/backend-ci.yml)
[![web-ci](https://github.com/polynomeer/monticker/actions/workflows/web-ci.yml/badge.svg)](https://github.com/polynomeer/monticker/actions/workflows/web-ci.yml)
[![e2e-ci](https://github.com/polynomeer/monticker/actions/workflows/e2e-ci.yml/badge.svg)](https://github.com/polynomeer/monticker/actions/workflows/e2e-ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**주가가 "왜" 움직였는지 보여주는 이벤트 중심 주식 관찰 플랫폼** — 뉴스·공시·거래량 이상·감정 신호를 차트 타임라인 위에 겹쳐 보여주고, 코딩 없이 만든 투자 규칙을 과거 데이터와 실시간 모의투자로 검증하며, 내 돈이 어디에 어떤 상태로 있는지 원장 기반으로 추적합니다.

> *monticker is an event-centric stock observation platform with a built-in quant strategy lab and investment wallet. It shows **why** a price moved by overlaying news, disclosures and volume anomalies on the chart timeline, lets users turn ideas into backtestable rules, and tracks money movement on an append-only ledger. Korean/US equities, Kotlin + Spring Boot + Next.js, MIT licensed.*

```
일반적인 주식 앱                          monticker
─────────────────────────              ─────────────────────────────────────────────
삼성전자 70,000원  +2.1%               삼성전자 10:24 급등
[차트]  [뉴스 목록]                       거래량: 5분 평균 대비 4.8배
                                          뉴스: "HBM 공급 확대 기대"
                                          Quant Lab 신호: "거래량 돌파 v1.2" 발동
                                          포워드 테스트 vs 백테스트 일치율: 94%
```

---

## 목차

- [누구를 위한 문서인가](#누구를-위한-문서인가)
- [주요 기능](#주요-기능)
- [아키텍처 한눈에 보기](#아키텍처-한눈에-보기)
- [기술 스택](#기술-스택)
- [빠른 시작](#빠른-시작)
- [환경 변수](#환경-변수)
- [프로젝트 구조](#프로젝트-구조)
- [테스트와 CI](#테스트와-ci)
- [문서 지도](#문서-지도)
- [프로젝트 현황과 로드맵](#프로젝트-현황과-로드맵)
- [엔지니어링 하이라이트](#엔지니어링-하이라이트)
- [기여하기](#기여하기)
- [면책 고지](#면책-고지)
- [라이선스](#라이선스)

---

## 누구를 위한 문서인가

| 방문 목적 | 이렇게 읽으세요 |
|-----------|----------------|
| **채용담당자 / 리뷰어** — 이 프로젝트가 무엇이고 기술적으로 어디까지 갔는지 10분 안에 파악하고 싶다 | 이 README의 [주요 기능](#주요-기능) → [아키텍처](#아키텍처-한눈에-보기) → [엔지니어링 하이라이트](#엔지니어링-하이라이트) 순으로 읽고, 더 깊이 보려면 [docs/portfolio.md](docs/portfolio.md)(기술 결정 요약)와 [docs/decisions/](docs/decisions/)(ADR 50건)를 펼쳐보세요. [docs/technical/troubleshooting-casebook.md](docs/technical/troubleshooting-casebook.md)는 실제로 겪은 문제 47건의 증상·원인·해결 기록입니다. |
| **개발자** — 로컬에서 띄우고 코드를 읽거나 기여하고 싶다 | [빠른 시작](#빠른-시작) → [프로젝트 구조](#프로젝트-구조) → [CONTRIBUTING.md](CONTRIBUTING.md). 설계 배경은 [docs/architecture.md](docs/architecture.md), 구현 심층은 [docs/technical/](docs/technical/README.md), 증권 도메인이 낯설면 [docs/domain/glossary-and-domain-knowledge.md](docs/domain/glossary-and-domain-knowledge.md)부터. |
| **일반 사용자** — 화면을 어떻게 쓰는지 알고 싶다 | [docs/manual/user-guide.md](docs/manual/user-guide.md)(화면별 사용 설명서)와 [docs/stock-knowledge/](docs/stock-knowledge/README.md)(주식·퀀트 용어 백과 24장). 실제 돈이 오가는 기능에 대한 주의사항은 [면책 고지](#면책-고지)를 꼭 읽어주세요. |

문서 전체 색인은 [docs/README.md](docs/README.md)에 있습니다.

---

## 주요 기능

monticker는 8개의 기능 축으로 구성됩니다. 모두 구현되어 있으며, 실제 브로커 연동처럼 외부 키·법무 검토가 필요한 항목은 상태를 따로 표시했습니다.

### 1. 실시간 시세 모니터링과 스크리너
약 200개 종목(KOSPI·KOSDAQ·NASDAQ·NYSE)의 실시간 시세를 거래대금·거래량·급등·급락 순으로 랭킹합니다. Kafka → STOMP WebSocket으로 브라우저까지 푸시되며, 500행 목록도 가상화로 DOM 노드 ~17개만 그립니다.

### 2. 이벤트 타임라인 (핵심)
가격 급등(`PRICE_SPIKE`)·거래량 급증(`VOLUME_SURGE`)을 EMA(α=0.1) 기반 적응형 임계값으로 탐지해 `stock_events`에 기록하고, 뉴스·공시·차트 패턴과 함께 차트 위에 마커로 겹쳐 보여줍니다. 가격이 아니라 **이벤트가 중심 도메인**입니다 ([ADR-003](docs/decisions/003-stock-events-central.md)).

### 3. 뉴스 · 공시 · AI 요약
네이버 뉴스·DART 공시를 수집해 종목에 매핑하고(Bloom Filter로 URL 중복 제거), Claude API로 "최근 이벤트·뉴스·가격 동향" 요약을 생성합니다. 국내 종목은 밸류에이션 스코어와 투자자별 매매 동향(KIS)도 제공합니다.

### 4. 관심종목 · 알림
그룹별 관심종목, 가격/거래량/RSI/이동평균/보유종목 하락 알림. Expo 푸시·이메일·주간 리포트로 전달되며 10분 쿨다운으로 중복 발송을 막습니다.

### 5. 모의투자 + 체결엔진 + 리스크 게이트
- **모의투자**: 가입 시 가상 계좌 1,000만원 자동 생성, 즉시 체결.
- **체결엔진**: 실제 거래소처럼 가격·시간 우선 CLOB 매칭, 부분 체결, 슬리피지 시뮬레이션.
- **리스크 한도**: 주문 **전**에 일일손실·집중도·VaR·종목수·거래빈도 5개 규칙을 동기 검사. 한도 초과는 체결 엔진에 도달하지 않습니다.
- **정산**: 체결 후 T+2 영업일 자동 정산 스케줄러.

### 6. 투자 지갑 (Investment Wallet)
모든 잔고 변경을 append-only 원장 이벤트로 기록하고, 잔고는 이벤트 replay로 계산합니다. 돈의 이동 지도(현금/예약금/평가액/정산대기), 투자 영수증, 주문 시점 감정 태그 × 수익률 분석, 하루 주문 리플레이, 투자 행동/생존 점수를 제공합니다. 원장 정합성은 야간 대조 작업으로 검증합니다 ([ADR-043](docs/decisions/043-ledger-pagination-and-reconciliation.md)).

### 7. Quant Lab — 룰셋 빌더 · 백테스트 · 포워드 테스트
코딩 없이 `IF 현재가 > MA20 AND 거래량 > 20일 평균×2 AND RSI BETWEEN 30,70 THEN 매수` 같은 규칙을 만들고, 수수료·세금·슬리피지를 반영한 백테스트(look-ahead bias 방지, 신뢰도 A~D 등급)와 장 마감 후 일 1회 평가되는 포워드 테스트로 검증합니다. Quant Analytics로 Markowitz 최적화·효율적 프론티어·손익통산 시뮬레이션·Kelly 포지션 사이징·차트 패턴 인식·시장 국면 분류도 제공합니다.

### 8. 전략 마켓 · 구독 · 커뮤니티
검증된 룰셋을 공유·판매하되 **룰셋 자체는 절대 클라이언트로 내려가지 않습니다** — 서버에서 평가한 신호만 전달하고, 소유자와 유료 구독자만 신호 토픽을 구독할 수 있습니다 ([ADR-035](docs/decisions/035-strategy-market-signal-access-control.md)). FREE/PRO/QUANT 플랜, 제작자 70% 수익 분배, 종목별 커뮤니티 댓글(매수·매도 권유는 키워드+AI로 fail-closed 차단)을 포함합니다.

### 실전투자 (BYOK) — 코드 완료, 라이브 검증 대기
monticker는 자체 증권사 라이선스가 없습니다. 사용자가 본인 명의 한국투자증권/토스증권 Open API 키를 등록하면 그 키로 대신 주문하는 **Bring-Your-Own-Key** 모델입니다 ([ADR-023](docs/decisions/023-commercialization-pivot.md)).

- 실주문·잔고 조회·취소, 손절/익절 조건부 주문(OCO), 목표 비중 리밸런싱, 토큰 자동 재발급
- 모든 실주문은 모의투자와 **같은 리스크 게이트**를 통과해야 합니다 — 브로커 네이티브 조건주문은 게이트 우회 위험 때문에 쓰지 않습니다 ([ADR-025](docs/decisions/025-real-brokerage-order-safety-gate.md))
- **AI 주문 제안**: LLM은 방향(BUY/SELL/HOLD)과 근거만 제안하고 수량·가격은 절대 정하지 않으며, "승인"은 제안 상태만 바꿉니다. 실제 제출은 사용자가 주문 폼에서 직접 눌러야 합니다 ([ADR-036](docs/decisions/036-ai-order-proposal.md))
- 브로커 자격증명은 AES-256-GCM으로 암호화 저장, 현금 예약은 원자적 조건부 UPDATE로 동시성 검증 완료
- 실제 앱키 발급(실명·사업자 인증)과 자본시장법 법무 검토는 사람의 액션이 필요해 대기 중 — [docs/human-action-items.md](docs/human-action-items.md)

---

## 아키텍처 한눈에 보기

**모듈러 모놀리스 + 비동기 워커 + Kafka + Redis + TimescaleDB.** MSA 대신 Spring Modulith로 모듈 경계를 강제하고([ADR-001](docs/decisions/001-modular-monolith.md), [ADR-019](docs/decisions/019-spring-modulith-boundary-conventions.md)), 워커만 역할별로 분리해 스케일합니다.

```
                    외부 데이터                          사용자
   KIS/Toss 실시간체결가 · 네이버 뉴스 · DART 공시         Next.js 15 (web)  ·  Expo (mobile)
                │                                              ▲  REST / STOMP WebSocket
                ▼                                              │
   ┌──────────────────────────┐    market.ticks    ┌───────────┴──────────────────────────┐
   │ backend/worker           │ ─────────────────▶ │ backend/api  (Spring Modulith)       │
   │  · MockPriceGenerator /  │      Kafka         │  auth · stock · marketdata · event   │
   │    KIS/Toss tick 수집    │ ◀───────────────── │  paper · matching · risk · wallet    │
   │  · CandleAggregator      │  trading.order-*   │  quant · analytics · brokerage       │
   │  · EventDetector (EMA)   │  search.index      │  subscription · settlement · ...     │
   │  · AlertEvaluator        │   (Outbox)         │  PriceBroadcaster → /topic/stocks/*  │
   │  · News/Disclosure 수집  │                    │  RiskChecker → MatchingEngine        │
   └──────────┬───────────────┘                    └───────────┬──────────────────────────┘
              │                                                │
              ▼                                                ▼
   ┌─────────────────────────────────────────────────────────────────────────────────────┐
   │ TimescaleDB (PostgreSQL 16)  ·  Redis 7  ·  MongoDB 7  ·  Elasticsearch  ·  Kafka   │
   │  ticks/candles hypertable       price cache    rule sets    검색 인덱스     이벤트 버스 │
   │  stock_events · ledger_events   orderbook      alert hist   (DB 폴백)               │
   └─────────────────────────────────────────────────────────────────────────────────────┘
              관측: Prometheus · Grafana · Alertmanager(Slack) · Jaeger(OTel) · Pinpoint
```

**두 개의 파이프라인**

```
이벤트 파이프라인   외부 데이터 → 워커 → Redis + TimescaleDB → EventDetector → stock_events → API → 차트 타임라인
퀀트 파이프라인     시세 이벤트 → IndicatorEngine → RuleEngine → Signal → Risk Check → 모의 주문 → 체결 → 전략 성과
```

**주문 처리는 Saga + Outbox + 원장**

```
POST /api/matching/orders
  → RiskChecker.preCheck (5개 규칙, 실패 시 REJECTED)
  → OrderSagaOrchestrator.reserveCash (원자적 UPDATE ... WHERE cash >= ?)
  → MatchingEngine (CLOB, 가격/시간 우선)
  → Fill → LedgerService (append-only 이벤트)
  → @Externalized OrderFilledEvent → event_publication (Outbox) → Kafka after commit
```

자세한 내용: [docs/architecture.md](docs/architecture.md) · [docs/technical/eda-event-driven-architecture.md](docs/technical/eda-event-driven-architecture.md) · [docs/technical/order-saga.md](docs/technical/order-saga.md)

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| **Backend** | Kotlin 2.0 · Spring Boot 3.5 · Spring Modulith(모듈 경계 + Outbox) · Spring Data JPA · Flyway(V1~V47) · Spring Security + JWT(JJWT) · OAuth2(Google/Kakao/Naver) · Resilience4j(Circuit Breaker) · Spring Kafka(`@RetryableTopic` DLT) · Micrometer + OpenTelemetry |
| **Realtime** | Apache Kafka(KRaft) · STOMP over SockJS · Go 1.22 market-gateway(goroutine-per-stock) |
| **Data** | PostgreSQL 16 + TimescaleDB(hypertable, continuous aggregate) · Redis 7 · MongoDB 7(룰셋·알림 이력) · Elasticsearch(검색, DB 폴백) |
| **Frontend** | Next.js 15(App Router) · React 19 · TypeScript · TanStack Query · Zustand · Tailwind CSS(Dracula 팔레트, 라이트/다크/고대비) · Apache ECharts(어댑터 패턴으로 차트 라이브러리 격리) · TanStack Virtual · Toss Payments SDK |
| **Mobile** | Expo 52 · React Native 0.76 · expo-router · Expo Push Notifications |
| **External API** | 한국투자증권 Open API · 토스증권 Open API · 네이버 뉴스 · DART · Yahoo Finance · Anthropic Claude · 토스페이먼츠 · SMTP |
| **Infra / Ops** | Docker Compose(프로파일 `full`/`kafka`/`msa`/`pinpoint`) · Kubernetes(kustomize, dev/prod overlay) · Nginx · Prometheus · Grafana · Alertmanager → Slack · Jaeger · Pinpoint APM · pg_dump 백업 CronJob + 복구 리허설 |
| **Testing** | JUnit 5 · MockK · MockMvc · Testcontainers(Postgres/Kafka) · Vitest · Playwright(E2E) · k6(부하) |
| **CI** | GitHub Actions — backend-ci(unit + integration), web-ci(lint/unit/audit), e2e-ci(Playwright), mobile-ci(typecheck), deploy-images, pr-review |

---

## 빠른 시작

### 사전 요구사항

| 도구 | 버전 | 비고 |
|------|------|------|
| Docker Desktop | 최신 | Postgres·Redis·MongoDB·Elasticsearch·MailHog 등을 컨테이너로 띄웁니다 |
| JDK | **21** | `backend/api`, `backend/worker` (Gradle wrapper 포함) |
| Node.js | **22** (20+) | `apps/web` |
| pnpm | **9.x** | 이 레포는 pnpm 워크스페이스입니다. **`npm install`을 쓰지 마세요** — `pnpm-lock.yaml`과 `package.json#pnpm` overrides가 깨집니다 |
| Go | 1.22 | 선택 — `--kafka` 모드의 market-gateway만 필요 |

### 1) 한 번에 띄우기 (권장)

```bash
git clone git@github.com:polynomeer/monticker.git
cd monticker
cp .env.example .env        # 외부 API 키 없이도 Mock으로 동작합니다
./dev.sh
```

`dev.sh`는 Docker 인프라 → API(8080) → Worker(8081) → Web(3000)을 순서대로 띄우고 헬스체크가 통과할 때까지 기다린 뒤 접속 URL을 출력합니다. 포트가 점유되어 있으면 자동으로 다음 빈 포트로 우회합니다. `Ctrl+C`로 전부 정리됩니다.

| 옵션 | 동작 |
|------|------|
| `./dev.sh` | 기본 — API + Worker(MockPriceGenerator) + Web. 외부 키 불필요 |
| `./dev.sh --kafka` | Kafka + Go market-gateway 추가. 실제 시세 파이프라인 경로(Go → Kafka → Worker)로 동작 |
| `./dev.sh --msa` | `--kafka` + 워커를 market/event/alert 역할로 분리 |
| `./dev.sh --pinpoint` | Pinpoint APM 포함(HBase 초기화 2~3분) |

접속:

| 서비스 | URL |
|--------|-----|
| Web | http://localhost:3000 |
| API (Swagger 없음 — 엔드포인트는 [architecture.md](docs/architecture.md#api-endpoints) 참고) | http://localhost:8080 |
| API health | http://localhost:8080/actuator/health |
| MailHog (인증 메일 확인) | http://localhost:8025 |
| Jaeger (분산 추적) | http://localhost:16686 |
| Grafana (`make monitoring-up` 후, admin / monticker) | http://localhost:3001 |

첫 화면(스크리너)은 로그인 없이 볼 수 있습니다. 회원가입하면 모의투자 계좌(1,000만원)가 자동 생성됩니다. 로컬에서는 `SOCIAL_MOCK_ENABLED=true`로 실제 OAuth 없이 소셜 로그인을 흉내낼 수 있습니다.

### 2) 수동으로 띄우기

```bash
make up                 # postgres + redis
docker compose up -d mongodb elasticsearch mailhog
ALLOW_INSECURE_DEV_SECRETS=true make api-run          # 터미널 1 — backend/api ./gradlew bootRun
cd backend/worker && ./gradlew bootRun                # 터미널 2
make web-install && make web-dev                      # 터미널 3 — pnpm --filter @monticker/web dev
```

> `ALLOW_INSECURE_DEV_SECRETS=true`가 없으면 API는 git에 커밋된 개발용 JWT/암호화 키를 감지하고 **기동을 거부**합니다(`InsecureSecretGuard`). `dev.sh`와 `docker-compose.yml`은 이 값을 자동으로 넣어주지만, 프로덕션에서는 절대 설정하지 말고 `JWT_SECRET`·`CREDENTIAL_ENCRYPTION_KEY`를 새로 발급하세요.

### 3) 전부 컨테이너로

```bash
make up-full            # api + worker + Kafka + 모니터링까지 docker compose --profile full
make up-msa             # 역할 분리 워커 3종 + Kafka
make down
```

### 테스트 실행

```bash
cd backend/api && ./gradlew test                 # 단위 테스트
cd backend/api && ./gradlew integrationTest      # Testcontainers 통합 테스트 (Docker 필요)
cd backend/worker && ./gradlew test
pnpm --filter @monticker/web test                # Vitest
pnpm --filter @monticker/web test:e2e            # Playwright (API·Web 기동 필요)
```

문제가 생기면 [docs/technical/troubleshooting-casebook.md](docs/technical/troubleshooting-casebook.md)와 `logs/api.log`, `logs/worker.log`, `logs/web.log`를 확인하세요.

---

## 환경 변수

로컬 개발은 `.env.example`을 복사하면 충분합니다 — 모든 외부 연동은 키가 없으면 Mock으로 대체됩니다. 아래는 자주 만지는 변수만 추린 것이며, 전체 목록은 `backend/api/src/main/resources/application.yml`과 [docs/deployment.md](docs/deployment.md)를 참고하세요.

### 필수 (프로덕션)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/monticker` / `monticker` / `monticker` | PostgreSQL(TimescaleDB) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `MONGODB_URI` | `mongodb://monticker:monticker@localhost:27017/monticker` | 룰셋·알림 이력 저장 |
| `ELASTICSEARCH_URI` | `http://localhost:9200` | 검색 인덱스 (없으면 DB 폴백) |
| `JWT_SECRET` | (개발용 기본값) | JWT 서명 키, 32바이트 이상. 프로덕션은 반드시 교체 |
| `CREDENTIAL_ENCRYPTION_KEY` | (개발용 기본값) | 브로커 자격증명 AES-256-GCM 키. 프로덕션은 반드시 별도 키 |
| `ALLOW_INSECURE_DEV_SECRETS` | `false` | 개발용 기본 시크릿으로 기동 허용 여부. 프로덕션은 `false` 유지 |
| `ALLOWED_ORIGINS` | `http://localhost:3000` | CORS 허용 Origin (쉼표 구분) |
| `APP_BASE_URL` | `http://localhost:3000` | 이메일 링크 등에 쓰는 웹 앱 URL |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | 웹 → API 서버 URL |

### 외부 연동 (없으면 Mock)

| 변수 | 설명 |
|------|------|
| `KIS_APP_KEY` / `KIS_APP_SECRET` | 한국투자증권 — 실시간 호가·체결가·투자자 동향. 없으면 MockPriceGenerator |
| `TOSS_PLATFORM_APP_KEY` / `TOSS_PLATFORM_APP_SECRET` | 토스증권 — 미국 전체 + 국내 최대 100종목 실시간 체결가 |
| `BROKERAGE_MOCK_ENABLED` | `true`면 실주문이 MockBrokerageClient로 감. **launch-plan Phase 0 완료 전 `false` 금지** |
| `ORDERBOOK_PROVIDER` | `yahoo` = Yahoo Finance 15분 지연 호가(계좌 불필요), 미설정 = Mock |
| `ANTHROPIC_API_KEY` | Claude — AI 뉴스 요약, 주문 제안, 댓글 필터 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 뉴스 API **및** 네이버 소셜 로그인(같은 앱 키) |
| `DART_API_KEY` | DART 공시 수집 |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 소셜 로그인. 로컬은 `SOCIAL_MOCK_ENABLED=true` |
| `TOSS_SECRET_KEY`, `PG_MOCK_ENABLED`, `NEXT_PUBLIC_TOSS_CLIENT_KEY` | 토스페이먼츠 구독 결제 |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | SMTP. 로컬은 MailHog(`localhost:1025`) — [CONTRIBUTING.md](CONTRIBUTING.md#이메일-로컬-테스트-mailhog) |
| `SLACK_WEBHOOK_URL` | Alertmanager → Slack. 비어 있으면 무발송 모드 |

### 파이프라인 / 운영

| 변수 | 설명 |
|------|------|
| `WORKER_ROLE` | `all`(기본) / `market` / `event` / `alert` — 워커 역할 분리 |
| `INGESTION_SOURCE` | `internal`(MockPriceGenerator → Kafka) / `kafka`(Go gateway → Kafka) |
| `KAFKA_BROKERS`, `KAFKA_PARTITIONS_*`, `KAFKA_CONSUMER_CONCURRENCY` | Kafka 토픽 선언·컨슈머 튜닝 ([ADR-040](docs/decisions/040-kafka-topic-declaration.md), [ADR-050](docs/decisions/050-realtime-pipeline-defaults-from-load-tests.md)) |
| `LEDGER_RECON_MODE` | 원장 대조 작업 모드 ([ADR-043](docs/decisions/043-ledger-pagination-and-reconciliation.md)) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Jaeger/OTel collector 주소 |

---

## 프로젝트 구조

```
monticker/
├── apps/
│   ├── web/                 Next.js 15 웹 클라이언트 (src/app 라우트, src/components, src/hooks)
│   └── mobile/              Expo 앱 — 관심종목 + 푸시 알림 수신
├── backend/
│   ├── api/                 Spring Boot API — 모듈러 모놀리스
│   │   └── src/main/kotlin/com/monticker/api/
│   │       ├── auth · stock · marketdata · event · news · disclosure · screener
│   │       ├── watchlist · alert · device · ai · community · stockscore · investor
│   │       ├── paper · matching · risk · wallet · settlement
│   │       ├── quant · backtest · analytics · subscription
│   │       ├── brokerage            ← BYOK 실주문 (KIS / Toss / Mock)
│   │       └── common               ← security, exception, ratelimit, idempotency, search
│   ├── worker/              Spring Boot 비동기 워커 — 시세 수집, 캔들 집계, 이벤트 탐지, 알림, 뉴스/공시
│   ├── quant-engine/        ⚠ ADR-049로 폐기 — 참고용으로만 남아 있음, 트래픽 0
│   └── trading-service/     ⚠ ADR-048로 폐기 — 동일
├── services/
│   └── market-gateway/      Go 시세 게이트웨이 (goroutine-per-stock → Kafka)
├── packages/types/          웹·모바일 공유 TypeScript 타입
├── infra/
│   ├── docker/              Dockerfile, nginx, db-backup 이미지
│   ├── k8s/                 kustomize base + dev/prod overlay
│   ├── monitoring/          Prometheus 알람 룰, Alertmanager, Grafana 대시보드
│   ├── db/                  백업·복구 리허설 스크립트
│   └── pinpoint/            Pinpoint APM
├── bench/                   k6 부하 테스트 시나리오
├── reports/                 부하·카오스 테스트 결과 보고서
├── docs/                    ← 문서 전체 (아래 문서 지도 참고)
├── .github/workflows/       backend-ci · web-ci · e2e-ci · mobile-ci · deploy-images · pr-review
├── .claude/                 Claude Code 서브에이전트·설정 (docs/workflow.md)
├── dev.sh                   원커맨드 로컬 기동 스크립트
├── docker-compose.yml       프로파일: (기본) · full · kafka · msa · pinpoint
├── Makefile                 up / up-full / up-msa / api-run / web-dev / k8s-* / db-backup ...
└── CLAUDE.md                Claude Code 프로젝트 지침 (ADR 규칙, 커밋 컨벤션)
```

---

## 테스트와 CI

| 스위트 | 도구 | 규모 (2026-09-09 기준) | 비고 |
|--------|------|------|------|
| `backend/api` 단위 | JUnit 5 + MockK + MockMvc | 469 | 서비스·컨트롤러·도메인 규칙 |
| `backend/api` 통합 | Testcontainers(Postgres, Kafka) | (별도 `integrationTest` 태스크) | 현금 예약 동시성, Outbox, Saga 복구, 원장 대조 |
| `backend/worker` | JUnit 5 + MockK | 80 | 탐지기, 캔들 집계, 알림 평가 |
| `apps/web` | Vitest + Testing Library | 37 | 훅·컴포넌트 |
| E2E | Playwright | 3 spec | 스크리너 렌더·필터·hydration, CI에서 API·Web 실기동 |
| 부하 | k6 (`bench/`) | — | 스크리너·주문·틱 파이프라인 SLO 검증 ([ADR-045](docs/decisions/045-performance-slo-and-verification-harness.md)) |
| 카오스 | 수동 시나리오 (`reports/`) | — | Kafka 브로커 다운, Redis 다운, DB failover 대응 판정 ([docs/resilience-plan.md](docs/resilience-plan.md)) |

CI는 PR마다 `backend-ci`(api·worker 매트릭스, unit + integration), `web-ci`(lint·unit·의존성 audit), `e2e-ci`, `mobile-ci`가 돕니다. 테스트 전략 상세는 [docs/technical/backend-test-strategy.md](docs/technical/backend-test-strategy.md).

---

## 문서 지도

전체 색인: **[docs/README.md](docs/README.md)**. 자주 찾는 문서만 추리면:

| 알고 싶은 것 | 문서 |
|-------------|------|
| 제품이 무엇이고 어디까지 됐나 | [docs/product.md](docs/product.md) |
| 시스템 구조·모듈 경계·API 목록 | [docs/architecture.md](docs/architecture.md) |
| DB 스키마 전체 | [docs/data-model.md](docs/data-model.md) |
| 왜 이렇게 결정했나 (ADR 50건) | [docs/decisions/](docs/decisions/) |
| 구현 심층 (32편) — EMA 탐지, CLOB, Saga, Outbox, 원장, 서킷브레이커… | [docs/technical/README.md](docs/technical/README.md) |
| 제품·비즈니스 판단의 근거 | [docs/domain/README.md](docs/domain/README.md) |
| 화면별 사용법 | [docs/manual/user-guide.md](docs/manual/user-guide.md) |
| 주식·퀀트 용어 백과 (24장) | [docs/stock-knowledge/README.md](docs/stock-knowledge/README.md) |
| 장애 대응 런북 (7종) | [docs/runbooks/README.md](docs/runbooks/README.md) |
| 프로덕션 배포·외부 서비스 등록 | [docs/deployment.md](docs/deployment.md), [docs/platform-api-keys.md](docs/platform-api-keys.md) |
| 상용 출시 체크리스트·법무·보안 | [docs/launch-plan.md](docs/launch-plan.md), [docs/security-review.md](docs/security-review.md), [docs/legal-review-brief.md](docs/legal-review-brief.md) |
| 대규모 트래픽 전환 계획 | [docs/scale-out-plan.md](docs/scale-out-plan.md) |
| 남은 일 | [docs/engineering-backlog.md](docs/engineering-backlog.md)(코드로 할 수 있는 것) · [docs/human-action-items.md](docs/human-action-items.md)(사람만 할 수 있는 것) |

---

## 프로젝트 현황과 로드맵

**MVP 완료 → 상용화 진행 중** ([ADR-023](docs/decisions/023-commercialization-pivot.md)). 모든 신규 기능은 실제 돈과 실제 브로커 자격증명이 걸린다는 전제로 보안·컴플라이언스·동시성 기준을 처음부터 적용합니다.

| 단계 | 상태 |
|------|------|
| 기능 구현 (8개 축 + BYOK 실주문 + AI 제안 + 커뮤니티) | ✅ 완료 |
| 상용화 선행 기술 부채 — 현금 예약 동시성, 브로커 서킷브레이커, 자격증명 암호화 | ✅ 완료 (2026-09-05) |
| 보안 점검·입력 검증 강화 (security-review / validation-hardening) | ✅ 완료 (PR #80) |
| 복원력 — 모니터링·알람·런북·백업/복구 리허설·카오스 테스트 | ✅ 설계 및 1차 검증 완료 |
| 실시간 시세 실데이터 라이브 검증 | 🟡 코드 완료, 플랫폼 앱키 발급 대기 |
| 실계좌 E2E 검증 (KIS 모의투자 / Toss 실거래) | 🟡 앱키·실명 인증 대기 |
| 법무 자문 (자본시장법·유사투자자문업·전자금융거래법·약관) | ⏳ 변호사 검토 대기 — [legal-review-brief.md](docs/legal-review-brief.md) |
| 결제(토스페이먼츠) 실연동 | ⏳ 스텁 — 유료 구독은 UI에서 "준비 중" |
| 퍼블릭 출시 | ⏳ 위 게이트 통과 후 ([launch-plan.md](docs/launch-plan.md) Phase 7) |

---

## 엔지니어링 하이라이트

채용담당자·리뷰어가 코드를 열기 전에 "무엇을 어떻게 풀었는가"를 빠르게 볼 수 있도록 추린 항목입니다. 각 항목은 ADR 또는 기술 문서로 근거가 연결됩니다.

| 주제 | 무엇을 했나 | 근거 |
|------|------------|------|
| **이벤트 중심 도메인** | 가격이 아닌 `stock_events`를 중심 객체로 두고 EMA(α=0.1) 적응형 임계값으로 급등·급증을 탐지, 분 단위 유니크 인덱스로 중복 방지 | [ADR-003](docs/decisions/003-stock-events-central.md), [ema-event-detection.md](docs/technical/ema-event-detection.md) |
| **모듈러 모놀리스 → 필요한 만큼만 분리** | Spring Modulith로 모듈 경계를 테스트(`ModulithStructureTest`)로 강제. quant-engine·trading-service를 MSA로 추출했다가 실측(트래픽 0, in-process bulkhead로 충분)으로 **폐기 결정을 ADR로 남김** | [ADR-001](docs/decisions/001-modular-monolith.md), [ADR-048](docs/decisions/048-retire-trading-service.md), [ADR-049](docs/decisions/049-retire-quant-engine.md) |
| **주문 처리 정합성** | Saga 오케스트레이션 + 보상 트랜잭션 + 5분 복구 스케줄러, Outbox(`event_publication`)로 at-least-once Kafka 발행, `X-Idempotency-Key` 멱등성, 원자적 조건부 UPDATE로 현금 예약 — Testcontainers 10스레드 동시성 테스트로 검증 | [ADR-011](docs/decisions/011-order-saga-orchestration.md), [ADR-008](docs/decisions/008-outbox-pattern-spring-modulith.md), [ADR-007](docs/decisions/007-idempotency-key-filter.md) |
| **이벤트 소싱 원장** | 잔고 컬럼 없이 append-only `ledger_events` replay로 잔고 계산, 스냅샷 페이지네이션, 야간 대조(mismatch 시 자동 교정 금지 → 런북) | [ADR-013](docs/decisions/013-append-only-ledger-wallet.md), [ADR-043](docs/decisions/043-ledger-pagination-and-reconciliation.md) |
| **CLOB 체결엔진 + 사전 리스크 게이트** | TreeMap 기반 호가 큐, 가격/시간 우선, 다단 슬리피지. 주문 전 동기 5규칙(일일손실·집중도·VaR·종목수·빈도) 검사, 실주문도 같은 게이트 강제 | [matching-engine-clob.md](docs/technical/matching-engine-clob.md), [ADR-025](docs/decisions/025-real-brokerage-order-safety-gate.md) |
| **실시간 파이프라인** | Kafka 파티션 키=stockId, 컨슈머 파티션 고정 배정, 전역 토픽 제거, 부하 테스트로 기본값 산출. TimescaleDB hypertable + continuous aggregate로 캔들 자동 집계 | [ADR-029](docs/decisions/029-price-broadcast-pipeline.md), [ADR-038](docs/decisions/038-broadcast-consumer-partition-assignment.md), [ADR-050](docs/decisions/050-realtime-pipeline-defaults-from-load-tests.md), [ADR-041](docs/decisions/041-timescale-hypertable-promotion.md) |
| **AI 가드레일** | LLM은 방향·근거만 제안, 수량·가격 결정 금지, 승인≠주문. 커뮤니티 댓글은 키워드+AI 하이브리드로 매수·매도 권유를 fail-closed 차단 | [ADR-036](docs/decisions/036-ai-order-proposal.md), [ADR-037](docs/decisions/037-stock-community-comments.md) |
| **BYOK 브로커 어댑터** | 브로커 무관 인터페이스 + KIS/Toss/Mock 구현, 프로바이더별 서킷브레이커, AES-256-GCM 자격증명 암호화, 토큰 자동 재발급, 취소의 실제 브로커 전달 | [ADR-026](docs/decisions/026-toss-brokerage-integration.md), [ADR-027](docs/decisions/027-brokerage-credential-refresh.md), [ADR-028](docs/decisions/028-brokerage-order-cancellation.md) |
| **복원력** | 외부 호출마다 Resilience4j CB + 로컬 폴백, Kafka DLT, 백테스트 bulkhead, 분산 락, graceful shutdown, Redis fail-open/fail-closed 구분. 알람 하나당 런북 하나 | [resilience-patterns.md](docs/technical/resilience-patterns.md), [resilience-plan.md](docs/resilience-plan.md), [runbooks/](docs/runbooks/README.md) |
| **보안** | JWT 15분 + refresh 7일 로테이션, 2-tier 레이트리밋(IP + userId), 룰셋 서버사이드 평가·클라이언트 비노출, STOMP 구독 인가, 하드코딩 시크릿 기동 차단(`ALLOW_INSECURE_DEV_SECRETS`) | [jwt-authentication.md](docs/technical/jwt-authentication.md), [security-review.md](docs/security-review.md), [ADR-035](docs/decisions/035-strategy-market-signal-access-control.md) |
| **관측·운영** | OpenTelemetry → Jaeger, Prometheus 알람 룰 + Alertmanager Slack, Grafana 대시보드, Pinpoint APM, pg_dump CronJob + 복구 리허설 스크립트 | [opentelemetry-tracing.md](docs/technical/opentelemetry-tracing.md), [db-failover.md](docs/runbooks/db-failover.md) |
| **문서화** | ADR 50건, 기술 심층 문서 32편, 트러블슈팅 사례집 47건, 도메인 문서 5편, 런북 7종, 용어 백과 24장 — 결정을 번복할 때는 새 ADR로 기존 ADR을 Supersede | [docs/README.md](docs/README.md) |

규모: 커밋 700+, Flyway 마이그레이션 47개, 백엔드 모듈 26개, 웹 라우트 37개, 테스트 580+ (2026-01 시작).

---

## 기여하기

이슈·PR 환영합니다. 시작하기 전에 [CONTRIBUTING.md](CONTRIBUTING.md)를 읽어주세요 — 로컬 환경, 브랜치·커밋 컨벤션(Conventional Commits, 스코프 `api`/`worker`/`web`/`mobile`/`types`/`infra`/`ci`/`docs`), PR 체크리스트, 그리고 **설계 결정을 바꿀 때는 반드시 ADR을 남기는 규칙**이 적혀 있습니다.

이 프로젝트는 Claude Code를 적극 활용해 개발됩니다. 서브에이전트·훅·워크플로 구성은 [docs/workflow.md](docs/workflow.md)와 [CLAUDE.md](CLAUDE.md)를 참고하세요.

---

## 면책 고지

- monticker는 **투자 자문 서비스가 아닙니다.** 화면의 모든 분석·점수·신호·AI 요약·주문 제안은 교육 및 시뮬레이션 목적의 참고 정보이며, 과거 성과는 미래 수익을 보장하지 않습니다. 실제 투자 판단과 책임은 사용자 본인에게 있습니다.
- **실전투자 기능은 사용자 본인의 증권사 계좌와 API 키로 실행됩니다.** monticker는 증권사가 아니며 자금을 보관하지 않습니다. 실계좌를 연동하기 전에 [docs/launch-plan.md](docs/launch-plan.md)의 게이트(Phase 0·1)와 [docs/legal-review-brief.md](docs/legal-review-brief.md)의 미해결 법률 질문을 반드시 확인하세요. 현재 이 기능은 라이브 검증과 법무 자문이 완료되지 않은 상태입니다.
- 세금 최적화·손익통산 시뮬레이션은 단순화된 규칙 기반이며 실제 세무 신고에 사용할 수 없습니다.

---

## 라이선스

[MIT](LICENSE) © 2026 augboot
