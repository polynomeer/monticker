# 기술 선택 결정 기록

monticker가 쓰는 **모든 기술**에 대해 무엇을 골랐고, 무엇과 비교했고, 왜 골랐는지를 한 곳에 모은 문서입니다. 이 문서는 [ADR](decisions/)을 대신하지 않습니다. ADR이 있는 결정은 요약하고 링크만 걸고, ADR이 없는 결정은 **근거를 실제로 어디서 찾았는지 출처와 함께** 기록합니다.

- 작성일: 2026-10-05 (기준 커밋 `5339e58`)
- 조사 방법: 빌드 파일·`package.json`·`go.mod`·`docker-compose.yml`·`infra/`의 실제 버전, ADR 001~063, `docs/` 전체, 코드 주석, `git log -S` / `--diff-filter=A`로 찾은 도입 커밋 메시지

## 읽는 법 — 근거 등급

| 등급 | 뜻 |
|------|-----|
| **ADR** | 대안 비교와 근거가 ADR에 있음 |
| **문서** | `docs/` 문서나 코드 주석에 근거가 있음 (대안 비교는 없을 수 있음) |
| **커밋** | 도입 커밋 메시지에만 근거가 한 줄 있음 |
| **없음** | 어디에서도 근거를 찾지 못함. 당시 이유를 **지어내지 않았습니다**. 대신 §9에 "지금 시점에서 이 선택을 유지할 이유와 재검토 조건"을 **사후 평가**로 따로 적었습니다 |

"없음"이 많다는 것 자체가 이 문서의 주요 발견입니다. 초기 골격(`dd55259`, `a983fbf`)과 라이브러리 추가 커밋 상당수는 "무엇을 추가했다"만 적고 "왜"를 남기지 않았습니다.

---

## 1. 아키텍처 스타일

| 결정 | 고려한 대안 | 근거 | 등급 |
|------|------------|------|------|
| 모듈러 모놀리스 | 처음부터 MSA | 1인 개발·MVP 속도, 경계는 모듈로 먼저 긋고 필요할 때 분리 | ADR [001](decisions/001-modular-monolith.md) |
| 경계 강제에 Spring Modulith | ArchUnit, 멀티 모듈 Gradle | ADR-001은 Modulith를 언급하지 않음. `8fff3e4`에서 이유 없이 추가됨. 경계 규약은 ADR-019, Outbox 근거는 ADR-008 | 없음 (도입), ADR [019](decisions/019-spring-modulith-boundary-conventions.md)/[008](decisions/008-outbox-pattern-spring-modulith.md) (사용 방식) |
| 분리했던 서비스(trading-service, quant-engine) 폐기 | 유지 | 트래픽을 받은 적 없는 복사본, 격리는 bulkhead가 이미 담당 | ADR [048](decisions/048-retire-trading-service.md), [049](decisions/049-retire-quant-engine.md) |
| 시세 수집만 Go 서비스로 분리 | 전부 JVM | 고루틴으로 다수 외부 연결을 싸게 처리, 핫패스를 JVM에서 격리. **부하가 아니라 포트폴리오·학습 목적**이라고 명시 | ADR [005](decisions/005-kafka-go-gateway-netty-broadcast.md) |
| 실 KIS 체결가 수집은 Go가 아니라 Kotlin | Go로 재작성 | 기존 Kotlin KIS 인프라(토큰·서킷브레이커) 재사용 | ADR [030](decisions/030-kis-realtime-tick-ingestion.md) |
| Netty broadcast-gateway (Kotlin) 제거 | 유지 | 프론트 클라이언트 0, CI 0, 인증·TLS 없음, STOMP가 이미 동작 | ADR [033](decisions/033-remove-netty-broadcast-gateway.md) |

## 2. 백엔드 (backend/api, backend/worker)

| 기술 | 버전 (실제 코드) | 역할 | 고려한 대안 / 근거 | 등급 |
|------|-----------------|------|-------------------|------|
| Kotlin | **1.9.25** | 백엔드 언어 | — | 없음 |
| JVM | Java **21** toolchain, Temurin 21 이미지 | 런타임 | 17로 시작했으나 Dockerfile(21 이미지)과 불일치로 빌드가 깨져 21로 맞춤 (`a46e7c9`). 설계 결정이 아니라 빌드 수정 | 커밋 |
| Gradle Kotlin DSL | wrapper 8.14.5, api·worker 독립 루트 | 빌드 | — | 없음 |
| Spring Boot | 3.5.0 | 프레임워크 | 첫 커밋부터 3.5.0 | 없음 |
| Spring Modulith | BOM 1.3.4 | 모듈 경계 검증(`ModulithStructureTest`), `@Externalized` Outbox | §1 참고 | 없음 / ADR-008·019 |
| Spring Data JPA + `JdbcTemplate` | Boot 관리 | ORM + 원시 SQL 혼용 (JpaRepository 41개 파일, JdbcTemplate api 61·worker 19개 파일). jOOQ·QueryDSL 없음 | ORM 선택 근거 없음. 원자적 조건부 UPDATE(`WHERE cash >= ?`)는 락 전략 실측 결과로 채택 — ADR-052 | 없음 / ADR [052](decisions/052-cash-reservation-lock-strategy.md) |
| Flyway | V1–V56 | 스키마 마이그레이션. api만 소유, worker는 `flyway.enabled: false` | 도구 선택 근거 없음. worker가 마이그레이션을 돌리지 않는 이유는 스키마 소유권을 api 하나로 두기 위함(worker `build.gradle.kts` integrationTest 주석) | 없음 / 문서 |
| Spring Batch | Boot 관리 | 7개 잡(정산·구독갱신·원장 대사·결제 대사 등)을 `@Scheduled` cron이 트리거 | Batch vs 순수 `@Scheduled` 근거 없음. ADR-024는 포워드 테스트에 Batch를 쓰지 않기로 함. design-review-2026-10 §: 다중 인스턴스 중복 실행 방지가 JobInstance 유일성에 "우연히" 기대고 있음 | 없음 |
| Spring Statemachine | 4.0.0 | 주문 상태 전이 검증 (주문당 인스턴스, 비영속, `Order.status`가 진실) | 코드 주석(`OrderStateMachineService.kt:8-14`)에 사용 방식만 있음 | 없음 |
| Spring Kafka `@RetryableTopic` + DLT | Boot 관리 | 컨슈머 재시도·포이즌 메시지 격리 | 재시도 토픽 분리로 파티션 블로킹 회피, 애노테이션 하나로 구성 | ADR [006](decisions/006-kafka-dlt-retry-strategy.md) |
| Spring Integration | Boot 관리 (worker) | `ingestion.source=kafka`일 때 틱 파이프라인 구성 | — | 없음 |
| Resilience4j | 2.4.0 | 외부 HTTP 서킷브레이커 | 라이브러리 선택 근거 없음. AOP 대신 프로그래매틱 API를 쓴 이유는 [circuit-breaker.md](technical/circuit-breaker.md) | 없음 / 문서 |
| HTTP 클라이언트 | Spring `RestClient` + JDK `HttpClient`. WebClient 없음 | 증권사·외부 API 호출 | 타임아웃 기준은 `HttpTimeouts.kt`, resilience-plan C4. 클라이언트 선택 근거 없음 | 없음 |
| 분산 락 | 자체 `@DistributedLock` (Redis SETNX), worker 전용. ShedLock·Redisson 없음 | K8s 다중 레플리카에서 `@Scheduled` 중복 실행 방지 | 필요성은 [resilience-patterns.md](technical/resilience-patterns.md). 라이브러리 대신 자체 구현한 이유는 없음 | 문서 / 없음 |
| Spring Security + OAuth2 Client | Boot 관리 | 로그인 (Google·Kakao·Naver) | 도입 커밋은 빌드 수정만 언급 | 없음 |
| JJWT | 0.12.6 | 액세스/리프레시 토큰 | — | 없음 |
| WebSocket STOMP | Boot 관리 | 실시간 시세 푸시 | ADR-005는 Netty를 선호했으나 ADR-033에서 프론트가 STOMP만 쓰고 실제 동작이 검증되었다는 이유로 STOMP 유지 | ADR [029](decisions/029-price-broadcast-pipeline.md), [033](decisions/033-remove-netty-broadcast-gateway.md) |
| springdoc-openapi | 2.5.0 | API 문서 (JWT Bearer 스킴) | — | 없음 |
| anthropic-java SDK | 2.34.0 (api, worker) | AI 요약·주문 제안·댓글 모더레이션·뉴스 감성 | §7 참고 | 없음 |
| logstash-logback-encoder | 7.4 (api만) | prod 프로필 JSON 구조화 로그 | "prod는 JSON(ELK/Loki 수집용), dev는 사람이 읽는 형식" (`4f98020`). 대안 비교 없음, 수집기도 미배포 | 커밋 |
| Micrometer + Prometheus registry | Boot 관리 | 메트릭 | §6 참고 | 없음 / ADR-054 |
| Guava | 33.7.1-jre (worker) | 뉴스 URL Bloom Filter | Redis `BF.ADD`는 인프라 의존성 추가라 기각 | ADR [010](decisions/010-bloom-filter-news-deduplication.md) |
| 테스트: JUnit5 + MockK + Testcontainers | MockK 1.14.11(api)/1.13.10(worker), Testcontainers 1.20.4 | 단위 / `integrationTest` 소스셋 | 컨텍스트 없는 순수 단위 테스트 우선은 [backend-test-strategy.md](technical/backend-test-strategy.md). 정합성 검증에 Testcontainers를 쓰는 이유는 ADR-045. MockK·Kotest 비교는 없음 | 문서 / ADR [045](decisions/045-performance-slo-and-verification-harness.md) |

## 3. 데이터 저장소·메시징

| 기술 | 버전 | 역할 | 고려한 대안 / 근거 | 등급 |
|------|------|------|-------------------|------|
| PostgreSQL | 16 (`timescale/timescaledb:latest-pg16`) | 주 저장소 (사용자·주문·원장·알림·뉴스·이벤트) | 원장·주문에 트랜잭션이 필요하다는 서술만 있음 ([scale-out-plan.md](scale-out-plan.md) 저장소 절). 버전·제품 비교 없음 | 없음 |
| TimescaleDB | `latest-pg16` (**버전 미고정**) | `candles_1m`/`candles_1d` hypertable + 압축 | Postgres 확장이라 커넥션·ORM·Flyway를 그대로 씀. InfluxDB·QuestDB 같은 별도 시계열 DB 회피. Continuous Aggregate는 ADR-041에서 기각, 원시 틱은 저장하지 않음 | ADR [002](decisions/002-timescaledb.md), [041](decisions/041-timescale-hypertable-promotion.md) |
| Redis | 7-alpine | 시세 캐시, Spring Cache, 레이트리밋, SETNX 락, 알림 쿨다운, 멱등성 캐시, pub/sub `alert:rules:changed` | Redis 자체의 근거 없음(ADR-004가 "이미 시세 캐시로 스택에 있음"을 전제). pub/sub은 모든 워커에 팬아웃해야 해서 컨슈머 그룹이 파티션을 나누는 Kafka 대신 채택(ADR-044). Lettuce 200ms 타임아웃은 느린 Redis가 Tomcat 스레드를 잡지 않게 하려는 것(`application.yml` 주석) | 없음 / ADR [044](decisions/044-alert-rule-in-memory-index.md) |
| Spring Cache 백엔드 | `RedisCacheManager`. Caffeine 없음 | 5개 캐시, TTL 5초~1시간, Redis 장애 시 fail-open | TTL별 근거와 "캐시가 DB보다 먼저 죽으면 안 된다"는 fail-open 근거는 `CacheConfig.kt` 주석. 로컬 캐시 대신 Redis를 쓴 이유는 없음 | 문서 |
| Redis Streams | **미사용** (실험 프로필 `TickOrderMonitor`만) | — | MVP 버스로 채택했다가 구현 전에 Kafka로 대체 | ADR [004](decisions/004-redis-streams-over-kafka.md) (Superseded) |
| Kafka | `apache/kafka:3.8.0`, KRaft 단일 브로커 | 틱·이벤트 버스, Modulith Outbox 외부화, `search.index` | **포트폴리오·학습 목적이라고 명시** (증권사 시세팀이 쓰는 도구, 재생 가능한 내구 로그). KRaft로 ZooKeeper 제거. 단일 브로커라 RF=1, 브로커 추가 시 RF=3. 토픽 auto-create 폐지. Pulsar·Redpanda는 "Kafka로 못 하는 게 없다"로 기각 | ADR [005](decisions/005-kafka-go-gateway-netty-broadcast.md), [040](decisions/040-kafka-topic-declaration.md), [050](decisions/050-realtime-pipeline-defaults-from-load-tests.md) |
| Kafka 직렬화 | JSON (프로듀서 ByteArray 값). Schema Registry 없음 | Go ↔ Kotlin 메시지 형식 | JSON은 "의도적 단순화", 운영 수준 해법은 Avro + Schema Registry로 명시. ByteArraySerializer는 Modulith `ByteArrayJsonMessageConverter`와 맞추기 위함 — StringSerializer로는 외부화가 100% 실패(`application.yml` 주석) | ADR-005, 문서 ([kafka-tick-pipeline.md](technical/kafka-tick-pipeline.md)) |
| MongoDB | 7 | **`rule_sets` 컬렉션 하나만** (퀀트 룰셋) | "스키마 자유도와 버전 히스토리 embed를 위한 설계" — `rule_set_versions` 테이블을 문서 내 embed로 대체 (`bfe2121`, `RuleSetDocument.kt` 주석). 대체된 쪽인 Postgres JSONB와의 비교는 없음. ADR 없음 | 커밋 |
| Elasticsearch | 8.13.4 + `analysis-nori` 커스텀 이미지, single-node | 6개 인덱스 전문 검색, 장애 시 DB 폴백 | nori는 한국어 형태소 분석 전제라 필수(공식 이미지에 없음). ES 자체는 "종목 검색을 LIKE에서 multi_match로 교체"(`7c5faae`)라는 커밋 설명만 있음. Postgres FTS·OpenSearch 비교 없음. 색인 경로는 Outbox 단일화(CDC 기각) | 커밋 / ADR [042](decisions/042-outbox-based-es-indexing.md) |
| HikariCP | max 20, min-idle 5, `statement_timeout` api 30s / worker 60s | 커넥션 풀 | worker 20은 "동시 쓰기를 위해 상향"(`bd2fdf9`). statement_timeout은 느린 쿼리가 풀을 고갈시키지 않게(resilience-plan A4). api 풀 크기 근거 없음. 동시 주문 ~100에서 고갈이 알려진 문제, PgBouncer 계획 있음 | 커밋 / 문서 |

## 4. 프론트엔드 (apps/web) · 공용 타입 · 모노레포

| 기술 | 버전 | 역할 | 고려한 대안 / 근거 | 등급 |
|------|------|------|-------------------|------|
| Next.js App Router | 15.5.25 (정확히 고정) | 웹 프레임워크. 페이지 39개 중 35개가 `"use client"` — 사실상 클라이언트 렌더링 | 선택 근거 없음. 15.1.0 → 15.5.25는 CVE 75건(치명 5건) 해소 목적([engineering-backlog.md](engineering-backlog.md)). 버전을 정확히 고정하는 이유: `pnpm.packageExtensions`가 `next@15.5.25` 키로 `@types/react` 문제를 막고 있어서 | 없음 / 문서 |
| React | 19 (web), 18.3 (mobile) | UI | 근거 없음. 한 워크스페이스에 두 메이저가 공존해 `@types/react` 팬텀 호이스트 버그가 났음([troubleshooting-casebook.md](technical/troubleshooting-casebook.md)) | 없음 |
| TypeScript | 5.9 (web), 5.3 (mobile) | 언어 | — | 없음 |
| TanStack Query | 5 | 서버 상태, 폴링 single-flight 중복 제거 | [request-deduplication.md](technical/request-deduplication.md). SWR 등과의 비교 없음 | 문서 |
| Zustand | 5 | 클라이언트 전용 상태 (테마·접근성·토스트) | "서버 상태를 복제하지 않는다"는 역할 규칙은 `.claude/agents/frontend-reviewer.md`. 다른 라이브러리 대비 근거 없음 | 없음 |
| Apache ECharts (어댑터 경유) | 6.1 | 모든 차트 | lightweight-charts를 대체(`49b7d8b`). D3·Recharts·직접 Canvas는 기각. 근거로 든 (1) 라이선스, (2) 명령형 API 교체 비용 **둘 다 검증 필요** — §10 참고. 5 → 6은 XSS 수정 목적 | 문서 ([chart-adapter-pattern.md](technical/chart-adapter-pattern.md)) |
| Tailwind CSS | 3.4, `darkMode: "class"` | 스타일 | — | 없음 |
| Dracula 팔레트 + 디자인 토큰 | `tailwind.config.ts` | 테마 | Bybit 테마에서 교체(`2d64715`, 색상 나열만 있음). 토큰화는 유지보수성(`cb9c39c`) | 없음 / 커밋 |
| Pretendard self-host | 1.3.9 dynamic subset | 한국어 타이포그래피 | "한국어 타이포그래피용 self-host"(`cb9c39c`). CDN 대신 self-host한 이유가 CSP(`font-src 'self'`)라는 연결은 기록에 없음 | 커밋 |
| Phosphor Icons | 2.1 | 아이콘 | 이모지 아이콘이 플랫폼마다 다르게 그려지고 접근성이 나쁨(`c60b0cb`). Lucide 등과의 비교 없음 | 커밋 |
| next-themes | 0.4 | 다크/라이트 전환 | — | 없음 |
| STOMP (`@stomp/stompjs`) + SockJS | `>=7.0.0`, `>=1.6.1` (**상한 없는 범위**) | 실시간 시세 수신 | 순수 WebSocket 대비 재연결·라우팅·구독 수명주기, 프록시 뒤 폴백, Spring 브로커와의 정합 ([realtime-price-architecture.md](technical/realtime-price-architecture.md)) | 문서 |
| TanStack Virtual | 3 | 스크리너 가상 스크롤 (500행 → DOM ~17개) | DOM 노드 과다([screener-virtualization.md](technical/screener-virtualization.md)). react-window 등과의 비교 없음 | 문서 |
| Vitest + Testing Library + jsdom | Vitest 4 | 단위·컴포넌트 테스트 | — | 없음 |
| Playwright | 1.62 | E2E (실 API + Next.js) | 목 단위 테스트로 못 잡는 실제 경계 버그(CSP, 실 API)를 잡기 위함 (`playwright.config.ts` 주석, `a523f41`) | 문서 |
| ESLint | 8, `next/core-web-vitals` | 린트 (`ignoreDuringBuilds: true`) | — | 없음 |
| 토큰 저장 | 액세스 = localStorage, 리프레시 = HttpOnly 쿠키 (`SameSite=Lax`, `Path=/api/auth`) | 인증 | 리프레시만 쿠키로 옮김. 액세스 토큰은 TTL 15분·사용처 19곳(WebSocket 인증 포함)이라 "이득 대비 위험이 과도"하다고 판단해 보류 (`df86b22`) | 커밋 / [security-review.md](security-review.md) C2 |
| CSP | `next.config.ts` 정적 헤더 | XSS 방어 | prod에서 `'unsafe-eval'` 제거. nonce 방식은 정적 프리렌더에 요청 컨텍스트가 없어 실패 → `'unsafe-inline'` 유지 (`next.config.ts` 주석) | 문서 |
| Toss Payments SDK | V2 2.8 | 구독 결제창 | 카드 입력을 토스 도메인에서 렌더해 PCI 범위를 우리 쪽에서 제외(`129a803`, CSP 주석). PG로 토스를 고른 이유는 §7 | 문서 / 없음 |
| `packages/types` | 손으로 작성 (~500 LOC) | 프론트 API 타입 | "API 타입을 로컬에 정의하지 않는다"는 규칙만 있음. springdoc이 있는데도 OpenAPI 코드 생성 대신 수기 작성한 이유는 없음 | 없음 |
| pnpm workspace | `pnpm@9.15.9` 고정 (corepack) | 패키지 매니저 | pnpm 자체 선택 근거 없음(`4465f56`). **9로 고정한 이유는 있음**: CI·Docker가 이미 9를 쓰는데 로컬 pnpm 11이 `package.json#pnpm` overrides를 조용히 무시한 회귀(`e206d7a`, `pnpm-workspace.yaml` 주석) | 없음 / 커밋 |

## 5. 모바일 (apps/mobile)

| 기술 | 버전 | 역할 | 근거 | 등급 |
|------|------|------|------|------|
| Expo + expo-router + React Native | Expo 52, RN 0.76 | 모바일 앱 (스크리너·종목 상세·관심종목·알림 룰·Expo 푸시 토큰 등록) | 근거 없음 (`04218d6`). 현황: 커밋 5개, ~690 LOC, 로그인·인증 헤더·WebSocket 없음, `@monticker/types` 미사용, CI는 타입체크만 | 없음 |
| Expo Push Notifications | — | 푸시 | FCM 직접 연동은 문서에 "권장"으로만 있고 미구현 | 없음 |

## 6. 인프라·CI·관측성

| 기술 | 버전 | 역할 | 고려한 대안 / 근거 | 등급 |
|------|------|------|-------------------|------|
| Docker Compose | — | 로컬 전체 스택. Kafka·Pinpoint·MSA는 프로필로 opt-in | Kafka를 opt-in으로 둔 이유: 기본 개발 흐름에 영향 주지 않기(ADR-005 Consequences) | ADR-005 |
| 앱 이미지 | Temurin 21 jdk→jre alpine, `node:20-alpine`, Go는 `golang:1.25-alpine` → `alpine:3.24`. distroless 없음 | 컨테이너 | Pinpoint 에이전트 단계 분리는 레이어 캐싱용(ADR-054). 베이스 이미지 근거 없음 | 없음 |
| Kubernetes + Kustomize | base + overlays(dev, prod). Helm 없음 | 배포 매니페스트 | 모니터링을 kustomize 루트에 넣어 compose와 단일 진실 공급원 유지(`dbe01fe`). Helm 대비 근거 없음. 상태 저장 서비스(Postgres·Redis·Kafka·Mongo·ES) 매니페스트가 없음 — 관리형 서비스 전제로 보이나 문서화되지 않음 | 커밋 / 없음 |
| NGINX Ingress + K8s DNS | — | API 게이트웨이, 서비스 디스커버리 | Kong·Envoy는 과함, Consul·Eureka 불필요(K8s DNS), Istio·Linkerd 보류 | ADR [009](decisions/009-kubernetes-service-discovery-nginx-gateway.md) |
| GitHub Actions | backend-ci, web-ci, e2e-ci, mobile-ci, pr-review, deploy-images | CI | e2e는 앱 간 실제 경계 버그 포착용([workflow.md](workflow.md)). web audit 스코프 분리는 `scripts/ci/audit-scope.js` | 문서 |
| GHCR | — | 이미지 레지스트리 (main push 시 빌드·푸시, 클러스터 배포 단계는 없음) | `GITHUB_TOKEN`만으로 충분하고 클라우드 계정이 필요 없음(`deploy-images.yml` 주석) | 문서 |
| Dependabot | 주간 (npm, gradle×2, gomod, docker) | 의존성 갱신 | — | 없음 |
| PR 자동 리뷰 | Claude Haiku 4.5, curl | PR 코멘트 | `*.kt *.tsx *.ts *.sql`만, 12,000바이트에서 잘림 — Go·YAML·Dockerfile은 리뷰되지 않음 | 없음 |
| OpenTelemetry (Micrometer bridge) + Jaeger | Jaeger all-in-one 1.57 | 분산 추적 | 세 계층(메트릭·추적·APM)의 역할 분담은 ADR-054. Jaeger를 Zipkin·Tempo 대신 고른 근거 없음 | ADR-054 / 없음 |
| Pinpoint | 3.1.0, 로컬 전용·기본 꺼짐 | APM | Jaeger만, Datadog/New Relic, async-profiler/JFR, Pinpoint만을 비교. 단 collector·web이 정상 부팅된 적이 없다는 미완 상태가 기록됨 | ADR [054](decisions/054-pinpoint-apm-alongside-jaeger.md) |
| Prometheus + Grafana + Alertmanager | Prometheus 2.51.2 (compose) / 2.53.1 (k8s), Grafana 10.4.2, Alertmanager 0.27.0 | 메트릭·대시보드 5개·알람 32개 → Slack | 도입 동기는 "사람에게 도달한 알람이 하나도 없음"(`1866a4c`, launch-plan Phase 3)과 "K8s에 메트릭 없음"(`dbe01fe`, resilience-plan P1-1). 제품 선택 근거 없음 | 커밋 / 없음 |
| 로그 수집 | **없음** (JSON 로그만 출력) | — | 수집기(Loki·ELK) 미정 | 없음 |
| k6 + Go gateway 재사용 + 쉘/Python 스텁 | — | 부하·카오스·정합성 검증 | HTTP/WS는 k6, k6가 못 만드는 Kafka 프로듀서 부하는 Go gateway — 한 도구로 다 하려는 비용이 분리 비용보다 큼 | ADR [045](decisions/045-performance-slo-and-verification-harness.md) |

## 7. 외부 서비스

| 영역 | 선택 | 근거 | 등급 |
|------|------|------|------|
| 증권사 (BYOK 실거래·시세) | KIS, 토스증권 | BYOK라 금융투자업 인가 없이 시작 가능(ADR-023). 토스는 provider 라우팅 계층으로 추가(ADR-026), 앱 수준 인증 허용·미국 종목 커버리지 0/51 → 51/51(ADR-031) | ADR [023](decisions/023-commercialization-pivot.md), [026](decisions/026-toss-brokerage-integration.md), [031](decisions/031-toss-realtime-tick-ingestion.md) |
| 투자자 동향·펀더멘털 | KIS 응답 재사용 | 새 공급자 없이 기존 연동 확장 | ADR [017](decisions/017-investor-flow-kis-integration.md), [018](decisions/018-stock-fundamentals-kis-reuse.md) |
| 뉴스 | 네이버 뉴스 API | 국내 뉴스 1순위 ([external-apis.md](external-apis.md) §3) | 문서 |
| 공시 | DART | 유일한 공식 공시 출처 (external-apis §4) | 문서 |
| 해외 호가 | Yahoo Finance (opt-in `ORDERBOOK_PROVIDER=yahoo`) | 근거 없음. external-apis는 오히려 "운영 비권장"이라고 적음 | 없음 |
| AI | Anthropic Claude, **모든 호출이 Haiku 4.5** | external-apis.md는 Claude를 기본 공급자로 두고 "배치는 Haiku, 품질 민감 작업은 Sonnet"을 권장. ADR-036·037은 "기존 Anthropic 연동 재사용"만 근거로 듦. 타 공급자 비교·Haiku 일괄 사용의 근거 없음 | 없음 |
| 결제(PG) | 토스페이먼츠 (기본 Mock) | 결제 방식(멱등성·실패 분류)은 ADR-053·059에 있으나 **PG사 선택**(PortOne·이니시스·Stripe 등 대비) 근거 없음 (`ad66067`) | 없음 |
| 소셜 로그인 | Google, Kakao, Naver | — | 없음 |
| 메일 | Spring Mail (기본 Gmail SMTP), 로컬 MailHog | — | 없음 |
| 실시세 데이터 원칙 | 실주문은 출처가 확인된 실시세로만 | Mock 시세로 실주문이 나가는 경로 차단 | ADR [055](decisions/055-price-provenance-gate-for-real-orders.md) |

## 8. 개발 도구

| 도구 | 역할 | 근거 | 등급 |
|------|------|------|------|
| Claude Code (서브에이전트 6개, 훅, 스킬) | 개발 워크플로 — spec → plan → 구현 → code-review → PR | 흐름은 [workflow.md](workflow.md). `.claude/settings.json` 훅이 파괴적 명령·시크릿 파일 쓰기를 막고 ktlint를 돌림 | 문서 |
| ktlint | Kotlin 포맷 | PostToolUse 훅으로 실행 | 없음 |

---

## 9. 근거가 기록되지 않은 선택 — 사후 평가 (2026-10-05)

아래는 **당시의 이유가 아닙니다.** 당시 이유는 기록이 없어 알 수 없습니다. 지금 시점에서 이 선택을 계속 가져갈 만한지와, 언제 다시 볼지를 적습니다. 상용화(ADR-023) 이후 실거래 자금이 걸리는 영역을 위에 두었습니다.

| 선택 | 지금 유지할 이유 | 재검토 조건 / 남은 위험 |
|------|-----------------|------------------------|
| **AI: Claude Haiku 4.5 일괄** | 비용·지연이 낮고, 주문 제안은 모의투자 한정·수동 요청(ADR-036)이라 오판 비용이 제한적 | 주문 제안이 실거래로 확장되거나 모더레이션 오탐/미탐이 보고될 때. external-apis.md 권장(품질 민감 작업은 Sonnet)과 다르므로 작업별 모델 선택 기준을 ADR로 남길 것 |
| **PG: 토스페이먼츠** | 카드 입력을 PG 도메인에서 처리해 PCI 범위 축소, 빌링키 기반 정기결제 지원, 같은 생태계(토스증권)와 맞음 | 실결제 전환 전에 수수료·정산 주기·해외카드 지원을 다른 PG와 비교해 ADR로 남길 것 |
| **Spring Batch** | 7개 잡에 재시작·실행 이력(JobRepository)이 필요한 정산·대사 작업이 포함됨 | 다중 인스턴스에서 중복 실행 방지가 JobInstance 유일성에 우연히 기대는 상태(design-review-2026-10). 명시적 락이나 리더 선출이 필요 |
| **자체 `@DistributedLock`** | 필요한 기능이 SETNX + TTL 정도라 의존성 추가 없이 충분 | api 쪽 스케줄러에는 락이 없음. 락 만료 후 작업이 계속 도는 경우(펜싱 토큰 없음)를 실거래 정산에 쓰기 전에 ShedLock 등과 비교할 것 |
| **MongoDB (rule_sets 하나)** | 룰셋 버전을 문서에 embed하는 모델이 자연스러움 | 컬렉션 하나 때문에 운영 대상 DB가 하나 늘어남. Postgres JSONB로 같은 모델이 가능하므로 운영 인프라를 확정할 때(K8s 상태 저장 서비스 결정) 다시 볼 것 |
| **Elasticsearch** | nori 형태소 분석·퍼지·부스팅·edge_ngram이 필요한 한국어 종목·뉴스 검색, DB 폴백이 있음 | Postgres FTS(+ pg_trgm)로 충분한지 비교한 적 없음. 관리형으로 갈 때 OpenSearch 비교 |
| **Kotlin 1.9 / Spring Boot 3.5 / JPA+JdbcTemplate 혼용** | 팀(1인)의 숙련도와 Spring 생태계(Modulith·Kafka·Batch)를 한 번에 씀. 원시 SQL은 원자적 조건부 UPDATE처럼 동시성이 걸린 곳에 필요 | Kotlin 2.x 이전, JPA와 JdbcTemplate이 같은 테이블을 쓸 때 1차 캐시 불일치 위험 |
| **Next.js (사실상 CSR)** | 라우팅·빌드·이미지 최적화를 한 번에 얻음 | SSR을 거의 쓰지 않아 정적 SPA(Vite)로도 충분할 수 있음. nonce CSP를 못 쓰는 제약도 여기서 나옴 |
| **`packages/types` 수기 작성** | 타입 수가 적고(~500 LOC) 빠르게 시작 가능 | 백엔드 DTO와 어긋나도 컴파일러가 못 잡음. springdoc이 이미 있으므로 openapi-typescript 생성을 검토 |
| **Expo 모바일** | 웹과 같은 React/TS로 푸시까지 빠르게 얻음 | 인증 없이 보호 API를 호출하는 상태 — 기능 확장 전에 인증부터 |
| **로그 수집기 미정** | JSON 로그는 이미 출력됨 | 실거래 장애 대응에 로그 검색이 필요. Loki vs ELK를 결정하고 ADR로 |
| **TimescaleDB `latest-pg16` 태그** | — | 고정되지 않은 태그는 재현성을 깨므로 버전을 고정할 것 |
| **STOMP/SockJS `>=` 범위** | — | 다음 메이저가 자동 설치될 수 있으므로 캐럿 범위로 바꿀 것 |

## 10. 문서와 코드가 다른 곳

조사 중 발견한 불일치입니다. 이 문서는 코드 기준으로 썼습니다. 각 문서의 수정은 별도 커밋으로 합니다.

| 문서 | 문서의 서술 | 실제 |
|------|------------|------|
| [architecture.md](architecture.md) Tech Stack, [portfolio.md](portfolio.md) | Kotlin 2.0 | 1.9.25 (처음부터) |
| [architecture.md](architecture.md) Tech Stack | Batch: Spring @Scheduled | Spring Batch 잡 7개 (`@Scheduled`는 트리거) — 같은 문서 다른 절은 Spring Batch라고 씀 |
| [architecture.md](architecture.md), 루트 README | MongoDB에 `rule_sets`, `alert_histories` | `alert_histories`는 Postgres 테이블(`V6`) + ES 인덱스. Mongo는 `rule_sets`만 |
| 루트 README, [portfolio.md](portfolio.md) | 캔들을 continuous aggregate로 집계 | ADR-041에서 기각. `CandleAggregator`가 메모리에서 집계 |
| [data-model.md](data-model.md) 상단 | Redis를 streams·dedup 키에 사용 | Streams 미구현, dedup은 Bloom Filter |
| [kafka-tick-pipeline.md](technical/kafka-tick-pipeline.md) | `market.ticks` 6 파티션 | ADR-040: 12 |
| [worker-performance.md](technical/worker-performance.md) | Java 17 | 21 |
| [backend-test-strategy.md](technical/backend-test-strategy.md) | mockk 1.13.13 | api 1.14.11 / worker 1.13.10 (모듈 간에도 다름) |
| [circuit-breaker.md](technical/circuit-breaker.md) | worker에 web starter 없음 | `1c48b17`에서 추가됨 |
| [ADR-008](decisions/008-outbox-pattern-spring-modulith.md) | Spring Modulith는 ADR-001에서 선택 | ADR-001은 Modulith를 언급하지 않음 |
| [chart-adapter-pattern.md](technical/chart-adapter-pattern.md) | lightweight-charts가 v4부터 BSL-1.1 | **검증 필요.** 저장소 안에서 확인할 근거가 없고, 업스트림은 Apache-2.0(+ 저작자 표시 요구)으로 알려져 있음. 교체 근거로 인용하기 전에 업스트림 LICENSE를 확인할 것 |
| [chart-adapter-pattern.md](technical/chart-adapter-pattern.md) | 명령형 API가 수십 개 파일에 산재 / 어댑터가 라이브러리를 격리 | 교체 직전 lightweight-charts를 import한 파일은 1개. 현재 `echarts`를 직접 import하는 파일이 4개 있어 격리가 깨져 있음 |
| [jwt-authentication.md](technical/jwt-authentication.md) | 액세스 토큰은 메모리에만, 쿠키 `SameSite=Strict` | localStorage, `SameSite=Lax` |
| [security-review.md](security-review.md) C2 | 두 토큰 모두 localStorage | 리프레시 토큰은 `df86b22`에서 HttpOnly 쿠키로 이동 |
| [realtime-price-architecture.md](technical/realtime-price-architecture.md), [latency-tradeoff.md](technical/latency-tradeoff.md) | `useStockPrice`는 REST 폴링, STOMP는 구현 예정 | `8defcf3`부터 SockJS/STOMP |
| `.claude/agents/frontend-reviewer.md` | Lightweight Charts, shadcn/ui, 토큰은 Zustand, API는 `api/` | ECharts, shadcn 없음, 토큰은 `services/auth.ts`, 디렉터리는 `services/` |
| `packages/types/src/index.ts` | web·mobile 공용 타입 | mobile은 의존하지 않음 |
| [deployment.md](deployment.md) | 토스 SDK V1 호출 형태 | V2 `.payment({customerKey}).requestBillingAuth(...)` |
| [architecture.md](architecture.md) 배포 절 | 로컬 compose가 NGINX 사용 | compose에 nginx 서비스가 한 번도 없었음. `infra/docker/nginx/nginx.conf`는 고아 파일 |
| [external-apis.md](external-apis.md) | 품질 민감 작업은 Sonnet / Yahoo 운영 비권장 / 미국 시세는 Alpha Vantage | 전부 Haiku / Yahoo 프로바이더 존재 / 미국 시세는 토스(ADR-031) |
| [ADR-054](decisions/054-pinpoint-apm-alongside-jaeger.md) | Jaeger를 유지해야 Go gateway가 보임 / 알람 27개 | Go gateway에 OTel 계측이 없음 / 알람 32개 |
| [workflow.md](workflow.md) | `scripts/claude-after-edit.sh` 훅, Sentry·Figma MCP, CI 목록 | 스크립트 없음(ktlint 인라인), MCP 흔적 없음, `deploy-images.yml` 누락 |
| `.github/dependabot.yml` 주석 | Gradle 루트 4개 | 2개 (ADR-048/049 이후) |
| Prometheus | compose 2.51.2 | k8s 2.53.1 |

## 11. ADR로 승격할 후보

CLAUDE.md 기준("두 가지 이상의 방식을 고려하고 하나를 선택", "외부 시스템 연동 방식 결정")에 해당하지만 ADR이 없는 결정입니다. 대안 비교를 실제로 해 본 뒤 작성해야 하므로, 이 문서에서 근거를 지어내 ADR로 만들지는 않았습니다.

1. AI 모델 선택 기준 (작업별 Haiku/Sonnet) — 실거래 확장 전
2. PG사 선택 (토스페이먼츠) — 실결제 전환 전
3. 스케줄 잡 중복 실행 방지 방식 (Spring Batch JobInstance vs 분산 락 vs 리더 선출)
4. 로그 수집 스택 (Loki vs ELK)
5. 운영 환경 상태 저장 서비스 (관리형 vs 자체 운영) — K8s에 매니페스트가 없는 상태를 명시
6. MongoDB 유지 vs Postgres JSONB 통합
7. 검색: Elasticsearch vs Postgres FTS
8. 프론트 API 타입: 수기 vs OpenAPI 코드 생성

## 12. 이 문서를 갱신하는 규칙

- 새 기술을 들이거나 빼면 **같은 PR에서** 이 문서의 해당 표에 행을 추가·수정합니다. 대안 비교가 있었다면 ADR을 쓰고 여기에는 링크만 겁니다.
- 라이브러리 추가 커밋에는 "무엇"뿐 아니라 "왜"를 한 줄 남깁니다. 이 문서의 "없음" 대부분이 그 한 줄이 없어서 생겼습니다.
- 버전은 이 문서에 적지 않아도 되는 것(빌드 파일이 진실)과 적어야 하는 것(문서에서 자주 인용되는 메이저 버전)을 구분합니다. 메이저 버전을 올리면 §10 같은 불일치가 생기지 않게 [architecture.md](architecture.md) Tech Stack도 함께 고칩니다.
