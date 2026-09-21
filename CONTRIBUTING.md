# monticker에 기여하기

이슈, 버그 리포트, 문서 수정, 기능 PR 모두 환영합니다. 이 문서는 **로컬 환경을 준비하고, 변경을 만들고, PR을 여는 데 필요한 모든 규칙**을 한곳에 모은 것입니다. 프로젝트 소개는 [README.md](README.md), 문서 색인은 [docs/README.md](docs/README.md)를 보세요.

> monticker는 **상용화 트랙**에 있습니다 ([ADR-023](docs/decisions/023-commercialization-pivot.md)). 실제 사용자 돈과 실제 브로커 자격증명이 걸린다는 전제로, 모든 변경에 보안·컴플라이언스·동시성 안전 기준이 처음부터 적용됩니다. "나중에 강화하자"는 없습니다.

## 목차

- [로컬 환경](#로컬-환경)
- [저장소 규칙](#저장소-규칙)
- [브랜치와 커밋](#브랜치와-커밋)
- [테스트](#테스트)
- [PR 체크리스트](#pr-체크리스트)
- [설계 결정은 ADR로](#설계-결정은-adr로)
- [문서 갱신 규칙](#문서-갱신-규칙)
- [영역별 주의사항](#영역별-주의사항)
- [로컬 개발 팁](#로컬-개발-팁)
- [Claude Code로 작업하기](#claude-code로-작업하기)

---

## 로컬 환경

### 요구사항

| 도구 | 버전 |
|------|------|
| Docker Desktop | 최신 |
| JDK | 21 |
| Node.js | 22 (20 이상) |
| pnpm | 9.x (`corepack enable` 권장 — `package.json#packageManager`가 `pnpm@9.15.9`로 고정) |
| Go | 1.22 (선택 — `--kafka` 모드) |

### 기동

```bash
cp .env.example .env
./dev.sh            # 인프라 → API → Worker → Web 순서로 띄우고 헬스체크 대기
```

`./dev.sh --help`로 `--kafka` / `--msa` / `--pinpoint` 옵션을 확인하세요. 수동 기동과 컨테이너 전체 기동은 [README.md 빠른 시작](README.md#빠른-시작)에 있습니다.

로그는 `logs/api.log`, `logs/worker.log`, `logs/web.log`에 쌓입니다. 기동 실패 시 `dev.sh`가 마지막 20줄을 출력합니다.

### 처음 띄웠는데 API가 안 뜬다면

- **`InsecureSecretGuard`가 기동을 막았다** — git에 커밋된 개발용 JWT/암호화 키를 그대로 쓰면 API가 거부합니다. `dev.sh`와 `docker-compose.yml`은 `ALLOW_INSECURE_DEV_SECRETS=true`를 자동으로 넣지만, `./gradlew bootRun`을 직접 치면 이 값을 붙여야 합니다.
- **포트 충돌** — `dev.sh`는 8080/8081/3000이 점유되면 다음 빈 포트로 우회하고 콘솔에 알려줍니다. 이전 `dev.sh` 잔여 프로세스만 정리하고, 관련 없는 프로세스는 절대 죽이지 않습니다.
- **Elasticsearch/MongoDB healthcheck 대기가 길다** — 첫 이미지 pull 이후에는 빨라집니다. ES가 없어도 검색은 DB 폴백으로 동작합니다.
- 그 외는 [docs/technical/troubleshooting-casebook.md](docs/technical/troubleshooting-casebook.md)에서 증상으로 검색하세요.

---

## 저장소 규칙

- **pnpm 워크스페이스만 사용합니다.** `npm install`·`yarn`은 `pnpm-lock.yaml`과 `package.json#pnpm` overrides(팬텀 호이스트 방지)를 깨뜨립니다. 패키지 추가는 `pnpm --filter @monticker/web add <pkg>` 형태로.
- **Java 21 툴체인** — Gradle wrapper가 강제합니다. 다른 JDK로 바꾸지 마세요.
- **Flyway 마이그레이션은 append-only** — 이미 머지된 `V*.sql`은 수정하지 않고 새 버전을 추가합니다. 현재 최신은 `V47`.
- **`backend/quant-engine`, `backend/trading-service`는 폐기됐습니다** ([ADR-048](docs/decisions/048-retire-trading-service.md), [ADR-049](docs/decisions/049-retire-quant-engine.md)). 기능 변경을 거기에 넣지 마세요. 해당 로직은 `backend/api`의 `matching`·`quant` 모듈에 있습니다.
- **Spring Modulith 경계** — `backend/api`의 모듈 간 참조는 `ModulithStructureTest`가 검증합니다. 다른 모듈의 `domain`/`infrastructure`를 직접 import하지 말고 `application` 서비스나 이벤트를 통하세요 ([ADR-019](docs/decisions/019-spring-modulith-boundary-conventions.md)).
- **시크릿은 절대 커밋하지 않습니다.** `.env`는 `.gitignore`에 있습니다. 새 외부 연동을 추가하면 `.env.example`에 빈 값으로 키 이름만 추가하고, 키가 없을 때 Mock으로 폴백되게 만드세요.

---

## 브랜치와 커밋

### 브랜치

`main`에서 분기해 PR로 머지합니다. 브랜치 이름은 자유이지만 `feat/`, `fix/`, `docs/` 접두어를 권장합니다. `main`에 직접 push하지 않습니다.

### 커밋 — Conventional Commits

```
<type>(<scope>): <subject>

[body]

[footer]
```

| type | 용도 |
|------|------|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 |
| `style` | 포맷 (로직 변경 없음) |
| `refactor` | 기능·버그가 아닌 코드 변경 |
| `perf` | 성능 |
| `test` | 테스트 추가·수정 |
| `chore` | 빌드·의존성·도구 |
| `ci` | `.github/workflows/` |
| `revert` | 되돌리기 |

| scope | 대상 |
|-------|------|
| `api` | backend/api |
| `worker` | backend/worker |
| `web` | apps/web |
| `mobile` | apps/mobile |
| `types` | packages/types |
| `infra` | infra/ |
| `ci` | .github/workflows/ |
| `docs` | docs/ |

규칙:

- subject는 명령형·소문자·마침표 없음·72자 이하. `feat(auth): add JWT refresh token support` ○ / `Added JWT refresh token.` ✗
- **한 커밋이 여러 스코프를 건드리면 스코프별로 나눕니다.** `feat(api): …` + `feat(web): …` 두 커밋.
- body는 *무엇을·왜*. *어떻게*는 코드가 말합니다.
- breaking change는 `feat!:` + `BREAKING CHANGE:` 푸터.
- **작업 단위가 끝나면 바로 커밋합니다.** 여러 작업을 한 커밋에 몰아넣지 않습니다.

---

## 테스트

| 대상 | 명령 | 비고 |
|------|------|------|
| api 단위 | `cd backend/api && ./gradlew test` | MockK, MockMvc. 빠름 |
| api 통합 | `cd backend/api && ./gradlew integrationTest` | Testcontainers(Postgres/Kafka). Docker 필요. `src/integrationTest/kotlin` |
| worker | `cd backend/worker && ./gradlew test` | |
| web 단위 | `pnpm --filter @monticker/web test` | Vitest |
| web lint | `pnpm --filter @monticker/web lint` | |
| web E2E | `pnpm --filter @monticker/web test:e2e` | Playwright. API·Web이 떠 있어야 함 |
| mobile | `pnpm --filter @monticker/mobile exec tsc --noEmit` | 타입 체크만 |
| 부하 | `bench/run.sh` | k6. [bench/README.md](bench/README.md) |

테스트 작성 기준은 [docs/technical/backend-test-strategy.md](docs/technical/backend-test-strategy.md)를 따릅니다. 요약:

- 서비스 로직은 MockK 단위 테스트, 컨트롤러는 MockMvc, DB·동시성·Outbox처럼 **실제 인프라 동작이 핵심인 것은 통합 테스트**로.
- 돈이 움직이는 코드(현금 예약, 원장, 체결, 정산)는 **동시성 테스트가 필수**입니다. 예: `CashReservationConcurrencyIntegrationTest`.
- 외부 API 클라이언트를 추가하면 서킷브레이커 OPEN 시 폴백 경로 테스트를 같이 넣습니다.
- 버그 수정 PR은 **재현 테스트를 먼저** 추가합니다.

---

## PR 체크리스트

`.github/pull_request_template.md`가 자동으로 채워집니다. 머지 전 확인:

- [ ] CI 그린 — `backend-ci`(unit + integration), `web-ci`(lint/unit/audit), `e2e-ci`, `mobile-ci`. **빨간 CI로 머지하지 않습니다.** 실패가 내 변경과 무관해 보여도 원인을 먼저 확인합니다.
- [ ] 스코프별로 커밋이 나뉘어 있다
- [ ] 새 설계 결정이 있으면 ADR을 추가했다 (아래 참고)
- [ ] 관련 문서를 갱신했다 — `architecture.md`(모듈·API·설정), `data-model.md`(스키마), `product.md` 로드맵 상태표, `manual/user-guide.md`(사용자 화면), `engineering-backlog.md`/`launch-plan.md`(체크리스트)
- [ ] 새 환경변수는 `.env.example`과 README 환경 변수 표에 추가했다
- [ ] 인증·인가·입력 검증·시크릿·브로커 호출을 건드렸다면 `security-reviewer` 서브에이전트 또는 `/security-review`를 돌렸다
- [ ] 돈이 움직이는 경로를 건드렸다면 동시성·멱등성·원장 정합성을 테스트로 증명했다
- [ ] 사용자에게 보이는 분석·점수·신호에는 "투자 자문이 아님" 고지가 유지된다

---

## 설계 결정은 ADR로

아래 중 하나라도 해당하면 **구현 전 또는 직후에** `docs/decisions/NNN-kebab-title.md`를 작성합니다:

- 두 가지 이상의 설계 방식을 고려하고 하나를 선택했을 때
- 기존 결정을 번복하거나 크게 수정했을 때
- 외부 시스템(PG, 브로커, AI 등) 연동 방식을 결정했을 때
- 도메인 모델의 핵심 구조(이벤트 소싱, CQRS, Saga 등)를 채택했을 때
- 비기능 요건(성능, 보안, 비용)이 설계에 영향을 줬을 때

번호는 기존 최대 + 1 (현재 050). 형식은 `Status / Context / Decision / Reasons / Consequences / Revisit When` — 템플릿 전문은 [CLAUDE.md](CLAUDE.md#architecture-decision-records-adrs). **기존 ADR을 번복할 때는 새 ADR을 쓰고 기존 ADR의 Status를 `Superseded by ADR-NNN`으로 바꿉니다.** 기존 ADR 본문을 고쳐 쓰지 않습니다.

폐기 결정도 ADR입니다 — [ADR-048](docs/decisions/048-retire-trading-service.md)처럼 "만들었지만 트래픽이 0이라 없앤다"는 판단과 근거 실측을 남기세요.

---

## 문서 갱신 규칙

- `docs/technical/`는 **어떻게 만들었는가**, `docs/domain/`은 **왜 그렇게 설계했는가**, `docs/manual/`은 **사용자가 어떻게 쓰는가**. 층을 섞지 않습니다.
- 사용자 매뉴얼에는 클래스명·테이블명·ADR 번호를 쓰지 않습니다.
- `launch-plan.md`, `engineering-backlog.md`, `human-action-items.md`는 살아있는 체크리스트입니다. 항목을 끝내면 날짜와 근거(커밋/PR)를 적고 체크합니다.
- 문서 변경은 `docs:` 커밋으로 분리합니다.

---

## 영역별 주의사항

### backend/api

- 컨트롤러는 얇게, 규칙은 `application`/`domain`에. 예외는 `GlobalExceptionHandler`가 표준 JSON으로 바꿉니다 — 비즈니스 규칙 위반은 `IllegalArgumentException`(400)/`IllegalStateException`(409), 리스크 초과는 `RiskLimitException`(422).
- 사용자별 남용 가능 엔드포인트에는 `@RateLimited`, 재시도가 위험한 POST에는 `X-Idempotency-Key` 필터 적용을 검토합니다.
- 실주문·모의주문 모두 **`RiskChecker.preCheck`를 우회하는 경로를 만들지 않습니다** ([ADR-025](docs/decisions/025-real-brokerage-order-safety-gate.md)). AI는 주문을 제출하지 않습니다 ([ADR-036](docs/decisions/036-ai-order-proposal.md)).
- 원장은 append-only입니다. `ledger_events`를 UPDATE/DELETE하는 코드는 리뷰에서 거부됩니다. 불일치는 자동 교정 대신 알람 + 런북([ledger-mismatch.md](docs/runbooks/ledger-mismatch.md)).
- 새 외부 HTTP 클라이언트는 `CircuitBreakerConfiguration`에 이름 있는 브레이커를 등록하고 OPEN 시 로컬 폴백을 정의합니다.
- Kafka 이벤트 발행은 `@Externalized` Modulith 이벤트(Outbox)로. `KafkaTemplate`을 트랜잭션 안에서 직접 호출하지 않습니다.

### backend/worker

- 워커 역할(`WORKER_ROLE=market|event|alert|all`)에 따라 컴포넌트가 `@ConditionalOnExpression`으로 켜집니다. 새 스케줄러는 어느 역할에서 도는지 명시하고, 레플리카 중복 실행을 막으려면 `@DistributedLock`을 붙입니다.
- 탐지기 상태는 인메모리입니다 ([ADR-046](docs/decisions/046-detector-state-in-memory.md)). 재시작 시 워밍업 동작을 깨뜨리지 않도록 주의.

### apps/web

- 서버 상태는 TanStack Query, 클라이언트 상태는 Zustand. 컴포넌트에서 `fetch`를 직접 부르지 않습니다.
- 디자인 토큰은 Tailwind 설정의 Dracula 팔레트(`dracula-*`)를 씁니다. 차트 색은 별도 테마입니다. Pretendard는 self-host(CSP).
- 차트는 `components/stock/chart/`의 어댑터 인터페이스를 통합니다 — ECharts API를 컴포넌트에서 직접 호출하지 않습니다 ([chart-adapter-pattern.md](docs/technical/chart-adapter-pattern.md)).
- 큰 목록은 TanStack Virtual로 가상화합니다.
- 실시간 구독은 `/topic/stocks/{id}` 등 종목 단위 토픽만. 전역 토픽은 제거됐습니다 ([ADR-039](docs/decisions/039-drop-global-market-topic.md)).

### 보안 전반

- 시크릿·토큰을 로그에 남기지 않습니다. `X-Request-Id`는 남깁니다.
- 브로커 자격증명은 `EncryptedStringConverter`를 통해서만 저장합니다.
- 룰셋 정의는 클라이언트로 직렬화하지 않습니다. 신호 결과만 내려갑니다.
- 인증·인가·입력 검증 변경은 PR 전에 `/security-review`를 돌립니다. 기준은 [docs/security-review.md](docs/security-review.md).

---

## 로컬 개발 팁

### 이메일 로컬 테스트 (MailHog)

`MAIL_USERNAME`/`MAIL_PASSWORD` 없이 API를 띄우면 인증·비밀번호 재설정 메일이 조용히 실패 로그로만 남습니다. `docker-compose.yml`의 MailHog가 이를 잡아줍니다:

```bash
docker compose up -d mailhog
cd backend/api && MAIL_HOST=localhost MAIL_PORT=1025 MAIL_USERNAME=test MAIL_PASSWORD=test ALLOW_INSECURE_DEV_SECRETS=true ./gradlew bootRun
```

> `spring.mail.properties.mail.smtp.auth`가 `true`로 고정돼 있어 `MAIL_USERNAME`/`MAIL_PASSWORD`를 비우면 MailHog가 자격증명을 검사하지 않는데도 "Authentication failed"가 납니다. 아무 문자열이나 넣으세요.

발송된 메일은 http://localhost:8025 에서 확인합니다. `dev.sh`와 `docker compose --profile full`은 이미 MailHog로 연결돼 있습니다.

### 실데이터 호가 (계좌 불필요)

`.env`의 `ORDERBOOK_PROVIDER=yahoo`로 두면 Yahoo Finance 15분 지연 호가를 씁니다. KIS 키가 있으면 `KIS_APP_KEY`/`KIS_APP_SECRET` 설정 시 실시간 호가·체결가로 자동 전환됩니다.

### 소셜 로그인

`SOCIAL_MOCK_ENABLED=true`(API) + `NEXT_PUBLIC_SOCIAL_MOCK=true`(web)로 실제 OAuth 앱 등록 없이 Google/Kakao/Naver 로그인 흐름을 흉내낼 수 있습니다.

### TimescaleDB 연속 집계

Worker가 `price_ticks` hypertable에 1초마다 틱을 쓰면 TimescaleDB가 `candles_1m_cagg`(매 1분)와 `candles_1d_cagg`(매 1시간) 뷰를 자동 집계합니다. `GET /api/stocks/{id}/candles`는 CAgg 뷰를 우선 쓰고 없으면 `CandleAggregator`가 채운 `candles_1m`로 폴백합니다. 상세: [timescaledb-candle-pipeline.md](docs/technical/timescaledb-candle-pipeline.md).

### 문서 스크린샷 다시 찍기

README와 사용자 매뉴얼의 `docs/images/*.png`는 Playwright 스크립트로 생성합니다. 화면이 바뀌면 다시 찍어서 함께 커밋하세요.

```bash
./dev.sh                                    # API·Worker·Web 기동
BASE=http://localhost:3000 API=http://localhost:8080 \
  pnpm --filter @monticker/web exec node scripts/capture-screenshots.mjs   # 전체
pnpm --filter @monticker/web exec node scripts/capture-screenshots.mjs wallet matching   # 일부만
```

- 기본 계정은 `docs-shot@monticker.local` / `Docs12345`(`EMAIL`/`PASSWORD`로 변경). 그 계좌에 모의투자 보유종목·룰셋·백테스트 결과·Mock 브로커 연동이 있어야 화면이 비어 보이지 않으니, 처음이면 API로 몇 건 만들어 두세요.
- 캡처는 1440px 폭 다크 테마이며 Next.js dev 오버레이는 자동으로 숨깁니다. 커밋 전에 `docs/images/`가 5MB를 넘지 않는지 확인하세요.

### 관측 도구

```bash
make monitoring-up      # Prometheus :9090, Grafana :3001 (admin / monticker)
make up-pinpoint        # Pinpoint APM :18080 (HBase 초기화 2~3분)
```

Jaeger는 `dev.sh`가 기본으로 띄웁니다 (http://localhost:16686).

### DB 백업·복구 리허설

```bash
make db-backup               # 로컬 compose Postgres → infra/db/backups
make db-restore-rehearsal    # 백업 → 스크래치 DB 복구 → 행 수 대조 → 삭제 (원본은 읽기만)
```

---

## Claude Code로 작업하기

이 저장소는 Claude Code 사용을 전제로 구성돼 있습니다.

- [CLAUDE.md](CLAUDE.md) — 프로젝트 지침 (ADR 규칙, 커밋 컨벤션, 참고 문서)
- `.claude/agents/` — 서브에이전트 6종: `backend-architect`, `security-reviewer`, `test-engineer`, `event-detector-reviewer`, `market-data-engineer`, `frontend-reviewer`
- [docs/workflow.md](docs/workflow.md) — 개발 흐름, 훅, CI 리뷰 연동

권장 흐름: 스펙/이슈 작성 → 계획 → 구현 → 테스트 → `/code-review` · `/security-review` → PR → CI + 자동 리뷰 → 머지. 인증·주문·원장을 건드리는 PR은 `security-reviewer`를 반드시 거칩니다.
