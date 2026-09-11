# monticker — 장애 대응 능력 판정과 검증 설계

> Read this when: "지금 상용 서비스로 열면 무엇이 터지는가"를 판단할 때, 또는 모니터링·
> 부하테스트·장애테스트를 설계·실행할 때. 규모 확장 계획은 [scale-out-plan.md](scale-out-plan.md),
> 결정 이력은 [decisions/](decisions/), 출시 체크리스트는 [launch-plan.md](launch-plan.md).

작성일: 2026-09-10 · 기준 커밋: `00d7bd6` · 상태: **판정 결과 + 설계안**

> **진행 (2026-09-11)**: §3의 **P0 7건 전부 구현·커밋 완료.** 상세는 §3.1.
> 구현 중 추가로 발견: `trading-service`가 `replicas: 2`였다 — 호가창이 pod 메모리에 있어
> 두 pod의 주문이 서로 체결되지 않는다(§E6이 "현재는 단일 인스턴스라 안전"이라고 적은 게
> 틀렸다). 1로 고정했다. 다음 단계는 P1-1(K8s 관측 스택)과 §5 부하 기준선.

---

## 0. 판정 요약

**상용 서비스로 지금 열면 안 된다.** 규모 때문이 아니라, **단일 의존성 하나가 죽으면
전체가 죽는 구조**이고 **그걸 알아챌 수단이 없기** 때문이다.

### 0.1 즉시 치명적인 것 (규모와 무관, 오늘 발생 가능)

| # | 문제 | 결과 | 근거 |
|---|------|------|------|
| **C1** | **Redis 장애 = 전체 API 장애** | 모든 `/api/**`가 500 | `RateLimitFilter`가 Redis 예외를 잡지 않는다 |
| **C2** | **알람이 아무 데도 안 간다** | 장애를 사람이 우연히 발견 | Alertmanager receiver가 빈 `default` |
| **C3** | **K8s에 모니터링 스택이 없다** | 운영 환경에 메트릭·알람 부재 | `infra/k8s/base`에 Prometheus/Grafana/Alertmanager 없음 |
| **C4** | **실브로커 HTTP 호출에 타임아웃이 없다** | KIS 지연 → 스레드 고갈 → 전면 장애 | `KisBrokerageClient`의 `RestClient.builder()`에 requestFactory 미설정 |
| **C5** | **서킷브레이커가 느린 호출에 반응하지 않는다** | C4를 막지 못한다 | `slowCallRateThreshold` 미설정, `TimeLimiter` 미사용 |
| **C6** | **readiness가 실제 서빙 가능 여부를 보지 않는다** | 죽은 pod에 트래픽이 계속 간다 | health group 설정 없음 → `readinessState`만 |
| **C7** | **백업이 스케줄에 연결돼 있지 않다** | 복구 불가 | `infra/db/backup.sh`를 호출하는 곳이 0건 |
| **C8** | **replicas ≥ 2에서 시세가 일부 사용자에게 안 간다** | 이미 발생 중 | [ADR-038](decisions/038-broadcast-consumer-partition-assignment.md) |

### 0.2 시나리오 카테고리별 판정

| 카테고리 | 대응 가능 | 부분 대응 | 대응 불가 |
|---------|:--------:|:--------:|:--------:|
| A. 인프라 의존성 장애 | 1 | 2 | **4** |
| B. 외부 API 장애 | 4 | 3 | 1 |
| C. 트래픽 과부하 | 2 | 3 | 3 |
| D. 배포·인프라 운영 | 2 | 2 | 3 |
| E. 데이터 정합성 | 2 | 2 | 3 |
| F. 보안·남용 | 3 | 2 | 1 |
| **합계** | **14** | **14** | **15** |

판정 기준:
- **대응 가능** — 방어 장치가 있고, 코드에서 확인되며, 실패해도 서비스가 성립한다.
- **부분 대응** — 장치는 있으나 구멍이 있거나(예: 예외를 삼킴) 검증된 적이 없다.
- **대응 불가** — 방어 장치가 없다. 발생하면 장애로 직행한다.

---

## 1. 이미 갖춰진 것 (공정한 출발점)

없는 것만 나열하면 그림이 왜곡된다. 이 프로젝트는 **패턴 자체는 상당히 잘 갖췄다.**

| 장치 | 구현 | 상태 |
|------|------|------|
| 서킷브레이커 | Resilience4j, 7개 CB (`kisApi`, `expoPush`, `naverNews`, `dartApi`, `tradingService`, `quantEngine`, `yahooFinance`) | 동작 |
| Kafka 재시도/DLT | `@RetryableTopic` 3곳 + `@DltHandler` ([ADR-006](decisions/006-kafka-dlt-retry-strategy.md)) | 동작 |
| Outbox | Spring Modulith `event_publication` + 5분 주기 재전송 ([ADR-008](decisions/008-outbox-pattern-spring-modulith.md)) | 동작 |
| Saga 보상 | `order_sagas` + 5분 주기 `recoverIncomplete()` ([ADR-011](decisions/011-order-saga-orchestration.md)) | 동작 |
| 멱등성 | `X-Idempotency-Key` + Redis 24h ([ADR-007](decisions/007-idempotency-key-filter.md)) | 동작(단 C1) |
| 레이트리밋 | 2계층 (Ingress 60rps/IP + 앱 IP/userId) | 동작(단 C1) |
| Bulkhead | `backtestExecutor` 전용 풀, 초과 시 429 | 동작 |
| Graceful shutdown | `server.shutdown: graceful`, 30s | 동작 |
| 분산 락 | Redis SETNX `@DistributedLock` | 동작 |
| 낙관적 동시성 | 현금 예약 `UPDATE ... WHERE cash >= ?` 단일 문장 + 동시성 통합테스트 | **검증됨** |
| Prometheus 알람 | 5개 (`ServiceDown`, `HighHttpErrorRate`, `CircuitBreakerOpen`, `HighJvmHeapUsage`, `HikariPoolNearExhaustion`) | **로컬만** |
| 트레이싱 | OpenTelemetry → Jaeger, `X-Request-Id` MDC | all-in-one(인메모리) |
| 대시보드 | Grafana `api-overview`, `tick-pipeline` | 로컬만 |

**문제는 패턴의 부재가 아니라 (a) 마지막 한 줄이 빠진 것, (b) 로컬에만 있는 것,
(c) 한 번도 검증된 적 없는 것이다.**

---

## 2. 장애 시나리오 카탈로그

각 항목: **트리거 → 현재 동작(코드 근거) → 판정 → 갭 → 대응**.

### A. 인프라 의존성 장애

#### A1. Redis 완전 장애 — **대응 불가 (C1)**

- **트리거**: Redis pod 재시작, 네트워크 분단, 메모리 초과 OOM
- **현재 동작**:
  ```kotlin
  // RateLimitFilter.isRateLimited — try/catch 없음
  val count = redis.opsForValue().increment("rate:$key") ?: 1L
  ```
  `RateLimitFilter`는 `/api/**` 전체에 걸리는 서블릿 필터다. Redis가 죽으면
  `RedisConnectionFailureException`이 필터에서 던져지고, `GlobalExceptionHandler`는
  `@RestControllerAdvice`라 **필터 레이어 예외를 잡지 못한다** → 컨테이너 기본 500.
  `IdempotencyFilter`(`redis.opsForValue().get()`)와 `RateLimitedAspect`도 동일하다.
- **판정**: **Redis는 캐시가 아니라 하드 의존성이다.** 로그인·조회·주문 전부 죽는다.
- **갭**: fail-open/fail-closed 정책이 설계된 적 없다.
- **대응**:
  - 레이트리밋 → **fail-open** (Redis 없으면 통과시키고 경고). 가용성 > 남용 방어.
  - 멱등성 → **fail-closed** (중복 주문 위험이 가용성보다 크다). 단 명시적 503 +
    `Retry-After`로 응답해 클라이언트가 재시도하게 한다.
  - 시세 캐시 → fail-open (DB 폴백).
  - 분산 락 → **fail-closed** (락 없이 스케줄러 중복 실행 금지).
  - 각 정책을 코드 주석이 아니라 **테스트로 고정**한다(§6의 카오스 실험 CH-01).

#### A2. Redis 부분 장애 (지연 증가) — **대응 불가**

- **트리거**: Redis 단일 스레드 블로킹(`KEYS`, 대용량 `SMEMBERS`), 메모리 스왑
- **현재 동작**: Lettuce 기본 타임아웃(60초)이 적용된다. 필터에서 60초 대기 → Tomcat
  스레드 고갈 → 전면 장애. A1보다 **더 나쁘다**(즉시 실패가 아니라 매달린다).
- **갭**: Redis 커맨드 타임아웃이 명시적으로 설정돼 있지 않다.
- **대응**: `spring.data.redis.timeout: 200ms`, `connect-timeout: 500ms`.
  시세 조회에 200ms 이상 걸리면 그건 이미 장애다.

#### A3. Postgres 장애 (primary 다운) — **부분 대응**

- **현재 동작**: HikariCP `connection-timeout: 3000` → 3초 후 예외 → 500.
  `HikariPoolNearExhaustion` 알람이 있다(로컬만).
- **판정**: 빠르게 실패하는 건 맞다. 하지만 **readiness가 DB를 보지 않아**(C6)
  pod는 계속 Ready 상태로 트래픽을 받는다. 사용자는 500을 계속 본다.
- **대응**: readiness 그룹에 `db` 포함. 단 **전체 pod가 동시에 NotReady가 되지 않도록**
  `livenessProbe`에는 절대 넣지 않는다(DB 장애가 pod 재시작 루프를 유발하면 복구가 더 느려진다).

#### A4. Postgres 커넥션 풀 고갈 — **부분 대응**

- **트리거**: 느린 쿼리(백테스트, 원장 전체 조회 §E2), 커넥션 누수
- **현재 동작**: pod당 20 커넥션, 3초 타임아웃. 알람 있음(로컬만).
- **갭**: `statement_timeout`이 없다. 느린 쿼리가 커넥션을 무한정 잡는다.
- **대응**: `statement_timeout` 도입(조회 5s, 배치 경로는 별도 데이터소스로 길게).

#### A5. Kafka 장애 — **부분 대응**

- **현재 동작**: 프로듀서 `acks=all` + `retries=3`. 실패 시 Outbox의
  `event_publication.completion_date = NULL`로 남고 5분마다 재전송 → **주문 이벤트는 안 잃는다.**
  컨슈머는 재연결 재시도. 시세는 유실되지만 시세는 최신값만 의미 있으므로 허용 가능.
- **판정**: 거래 경로는 잘 설계됐다.
- **갭**: **복제 계수 1**(단일 브로커)이라 `acks=all`이 실질적으로 `acks=1`이다
  ([ADR-040](decisions/040-kafka-topic-declaration.md)). 브로커 디스크가 죽으면 유실된다.
  컨슈머 랙 메트릭도 없다.
- **대응**: 멀티 브로커(Phase 1) + `min.insync.replicas=2` + 랙 알람.

#### A6. Elasticsearch 장애 — **대응 가능**

- **현재 동작**: 모든 ES 호출이 `try/catch → log.warn` 후 DB 폴백.
  `ScreenerService.search`, `EventSearchService` 등 전부 동일 패턴.
- **판정**: **의도적으로 잘 설계됐다.** ES가 죽어도 검색이 DB LIKE로 떨어질 뿐 서비스는 산다.
- **갭**: 폴백 발생을 세는 메트릭이 없어, ES가 죽은 채 몇 주가 지나도 모른다.
- **대응**: `search_fallback_total{index}` 카운터 + 알람.

#### A7. MongoDB 장애 (룰셋 저장소) — **대응 불가**

- **현재 동작**: 확인된 폴백 없음. Quant Lab 룰셋 조회 실패 → 퀀트 기능 전면 중단.
- **판정**: 영향 범위가 퀀트 모듈에 한정되므로 치명도는 중간.
- **대응**: 룰셋 조회 실패 시 명확한 503 + 나머지 서비스 영향 차단 확인(카오스 CH-04).

### B. 외부 API 장애

| # | 대상 | 현재 방어 | 판정 | 갭 |
|---|------|----------|------|-----|
| B1 | KIS 주문 API | CB `kisApi`(50%/6회/30s) | **부분** | **타임아웃 없음(C4)**, 느린 호출에 CB 무반응(C5) |
| B2 | KIS 실시간 WS | 재연결 로직 | 부분 | 시퀀스 갭 감지 없음, 재구독 검증 안 됨 |
| B3 | Yahoo Finance | CB `yahooFinance` + Mock 폴백 | **가능** | — |
| B4 | Naver 뉴스 | CB `naverNews` + Mock 폴백 | **가능** | — |
| B5 | DART 공시 | CB `dartApi`(10분 대기) + 빈 리스트 | **가능** | — |
| B6 | Expo Push | CB `expoPush` + 이메일 폴백 | **가능** | 이메일도 실패하면 알림 유실(기록만 남음) |
| B7 | SMTP | 없음 | **불가** | 타임아웃·CB 없음. `JavaMailSender` 동기 호출 |
| B8 | Anthropic (AI 요약/주문제안) | `@RateLimited` | 부분 | 타임아웃·CB 확인 안 됨. 비용 폭주 방어 없음 |

#### B1 상세 — **가장 위험한 항목**

```kotlin
private val restClient = RestClient.builder()
    .baseUrl(baseUrl)
    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
    .build()          // ← requestFactory 미설정 = 타임아웃 무제한
```

그리고 서킷브레이커는 **실패율만** 본다:

```kotlin
.failureRateThreshold(50f).slidingWindowSize(6)      // slowCallRateThreshold 없음
```

**KIS가 죽지 않고 느려지면 CB는 절대 열리지 않는다.** 호출이 반환되지 않으니 실패로
집계되지 않기 때문이다. Tomcat 스레드가 하나씩 KIS 응답을 기다리며 쌓이고,
스레드풀이 마르면 **주문과 무관한 모든 요청까지 죽는다.**

이건 실제 사용자 자금이 걸린 경로다. 대응:
```kotlin
RestClient.builder()
    .requestFactory(JdkClientHttpRequestFactory().apply {
        setReadTimeout(Duration.ofSeconds(5))
    })
// + CircuitBreakerConfig
.slowCallRateThreshold(50f)
.slowCallDurationThreshold(Duration.ofSeconds(3))
```
**모든 외부 HTTP 클라이언트에 타임아웃이 있는지 전수 감사**가 선행되어야 한다(§9).

### C. 트래픽 과부하

#### C1s. 개장 직후 스파이크 — **대응 불가**

- **트리거**: 09:00 KST. 접속·조회·틱이 동시에 폭증한다. 예측 가능하지만 급격하다.
- **현재 동작**: HPA(CPU 70%, 2~6 replica). **HPA는 반응형이라 이미 늦다** —
  스케일아웃 판단(15~30s) + pod 기동(Spring Boot ~30s + readiness 30s) = **1분 이상**.
  그동안 기존 pod가 버텨야 한다.
- **갭**: 예약 스케일링 없음. 워밍업 없음. `maxReplicas: 6`이 상한.
- **대응**: 08:45에 미리 스케일업하는 `CronJob`(또는 KEDA cron scaler).
  예측 가능한 부하에 반응형 오토스케일링만 쓰는 건 설계 실수다.

#### C2s. 급등장 틱 폭증 — **대응 불가**

- **현재 동작**: `market.ticks` 파티션 1개 → 컨슈머 병렬성 1 → 랙 무한 증가.
  백프레셔가 없어 랙이 쌓이기만 한다.
- **대응**: [ADR-040](decisions/040-kafka-topic-declaration.md) 파티션 + 백프레셔 시
  **최신 틱만 남기고 드롭**([scale-out-plan §6.1.2](scale-out-plan.md)).

#### C3s. WebSocket 동접 폭증 — **대응 불가**

- **현재 동작**: `/topic/market`이 모든 틱을 모든 클라이언트에 보낸다
  ([ADR-039](decisions/039-drop-global-market-topic.md)). 동접 1,000이면 붕괴.
  연결 수 상한도, 인증된 사용자당 연결 제한도 없다.
- **대응**: ADR-038/039 + **pod당 연결 수 상한**과 초과 시 거절.

#### C4s. 백테스트 폭주 — **대응 가능**

- **현재 동작**: `backtestExecutor`(core=2/max=4/queue=20) bulkhead, 초과 시 429.
  `@RateLimited` 10회/시간.
- **판정**: 이 부분은 제대로 설계됐다.
- **갭**: 큐 깊이 메트릭 없음.

#### C5s. 스크리너 캐시 스탬피드 — **부분 대응**

- TTL 5초 + 파라미터 조합 폭발. 만료 순간 동시 요청이 전부 DB로.
- 대응: [scale-out-plan §6.4.3](scale-out-plan.md) ZSET 사전 계산.

#### C6s. 봇/스크래핑 — **부분 대응**

- 2계층 레이트리밋이 있으나 IP 기반이라 분산 봇에 무력. `X-Bench: true` 헤더로
  **레이트리밋을 우회할 수 있다**(`RateLimitFilter` 첫 줄). 벤치마크용 편의가
  프로덕션에 그대로 남아 있다 → **운영 프로파일에서 비활성화 필수**.

### D. 배포·인프라 운영

| # | 시나리오 | 판정 | 근거 / 갭 |
|---|---------|------|----------|
| D1 | 롤링 배포 중 요청 유실 | **가능** | graceful shutdown 30s + readiness |
| D2 | 배포 중 캔들 유실 | **부분** | `@PreDestroy` flush는 graceful 종료에서만. OOMKill·노드 손실은 유실 |
| D3 | 노드 손실 (replica 전멸) | **불가** | **PDB·anti-affinity·topologySpread 전부 없음** → 2 replica가 한 노드에 뜰 수 있다 |
| D4 | Flyway 마이그레이션 실패 | 부분 | 기동 실패로 드러남. 롤백 스크립트 없음 |
| D5 | 스키마 변경 중 구/신 버전 공존 | **불가** | 롤링 배포 중 두 버전이 같은 DB를 본다. expand-contract 규약 없음 |
| D6 | 설정 오류 (env 누락) | 부분 | 일부는 기동 실패, 일부는 조용히 기본값 |
| D7 | 백업/복구 | **불가** | 스크립트는 있으나 **어떤 스케줄에도 연결 안 됨**(C7). 복원 리허설 기록 없음 |

#### D3 상세

`infra/k8s/base/*.yaml` 전체에 `PodDisruptionBudget`, `affinity`,
`topologySpreadConstraints`가 **하나도 없다**. 결과:
- 노드 1대가 죽으면 api 2 replica가 모두 그 노드에 있었을 경우 전면 장애.
- 클러스터 업그레이드(노드 drain) 시 동시에 evict되어 다운타임.

### E. 데이터 정합성

| # | 시나리오 | 판정 | 근거 |
|---|---------|------|------|
| E1 | 중복 주문 (네트워크 재시도) | **가능** | 멱등성 키 24h (단 Redis 의존 §A1) |
| E2 | 잔고 드리프트 | **불가** | 컬럼 잔고 vs 원장 대사 없음 ([ADR-043](decisions/043-ledger-pagination-and-reconciliation.md)) |
| E3 | Saga 미완료 잔류 | **가능** | 5분 주기 `recoverIncomplete()` |
| E4 | 캔들 유실 | **부분** | 인메모리 상태, 리밸런스·강제종료 시 유실 |
| E5 | ES 드리프트 | **불가** | dual-write 실패를 `log.warn`으로 삼킴 ([ADR-042](decisions/042-outbox-based-es-indexing.md)) |
| E6 | 이중 체결 / 미체결 | **불가 → 수정됨** | ~~단일 인스턴스라 안전~~ **틀렸다** — `trading-service.yaml`이 `replicas: 2`였고 호가창은 pod 메모리에 있다. 두 pod에 나뉜 주문은 서로 체결되지 않는다. `6871c0d`에서 1 + Recreate로 고정 |
| E7 | 조용한 계산 오류 | **불가** | `VOLUME_SURGE`가 무효 SQL로 **한 번도 발동한 적 없었던** 전례([ADR-044](decisions/044-alert-rule-in-memory-index.md)) |

**E7이 이 저장소의 구조적 패턴이다.** `runCatching`/`catch` 후 `WARN`/`DEBUG`만 남기는
코드가 곳곳에 있어, **기능이 죽어도 아무 신호가 나지 않는다.**
`price_ticks`가 한 번도 쓰이지 않았던 것, hypertable이 한 번도 적용되지 않았던 것,
`init-timescaledb.sql`이 어디에도 연결되지 않았던 것 모두 같은 뿌리다.

### F. 보안·남용

| # | 시나리오 | 판정 | 근거 / 갭 |
|---|---------|------|----------|
| F1 | 브로커 크리덴셜 유출 | **가능** | AES-256-GCM 암호화 저장(`EncryptedStringConverter`) |
| F2 | 브루트포스 로그인 | **가능** | IP당 10회/분 (단 §C6s 우회 헤더) |
| F3 | JWT 탈취 | **가능** | Access 15분 + refresh 회전 |
| F4 | 전략 역공학 | 부분 | 서버 평가 + 시그널 레이트리밋 ([ADR-035](decisions/035-strategy-market-signal-access-control.md)) |
| F5 | 레이트리밋 우회 | **불가** | `X-Bench: true` 헤더 |
| F6 | 크리덴셜 스터핑 | 부분 | IP 기반만. 계정 단위 잠금 없음 |

---

## 3. 판정 종합 — 무엇부터 고치나

**착수 순서는 "고치는 비용 ÷ 막는 손해"다.** 아래 P0는 전부 **하루 이내 작업**이면서
전면 장애를 막는다.

| 우선 | 항목 | 작업량 | 막는 것 |
|------|------|-------|--------|
| **P0-1** | Redis 실패 정책 (fail-open/closed) + 타임아웃 200ms | S | 전체 API 장애 (A1, A2) |
| **P0-2** | 모든 외부 HTTP 클라이언트 타임아웃 + `slowCallRateThreshold` | S | 스레드 고갈 전면 장애 (B1, C4/C5) |
| **P0-3** | Alertmanager 실제 채널 연결 | S | 무인지 장애 (C2) |
| **P0-4** | `X-Bench` 우회 헤더를 prod 프로파일에서 제거 | XS | 레이트리밋 무력화 (F5) |
| **P0-5** | 백업 스케줄 연결 + **복원 리허설 1회** | M | 복구 불가 (D7) |
| **P0-6** | readiness에 `db` 포함 (liveness에는 절대 넣지 않음) | S | 죽은 pod에 트래픽 (A3, C6) |
| **P0-7** | PDB + topologySpreadConstraints | S | 노드 손실 전면 장애 (D3) |
| **P1-1** | K8s 모니터링 스택 (Prometheus/Grafana/Alertmanager) | L | 운영 관측 부재 (C3) |
| **P1-2** | 삼켜지는 예외에 메트릭 부착 | M | 조용한 기능 사망 (E7) |
| **P1-3** | `statement_timeout` | S | 커넥션 풀 고갈 (A4) |
| **P1-4** | 개장 전 예약 스케일업 | S | 개장 스파이크 (C1s) |

Phase 0의 ADR-038~045는 이 목록과 **직교한다** — 저쪽은 규모, 이쪽은 가용성이다.
**P0는 Phase 0보다 먼저 한다.** Phase 0을 검증하려면 그전에 관측이 가능해야 한다.

### 3.1 P0 실행 기록 (2026-09-11)

| 항목 | 커밋 | 검증 |
|------|------|------|
| P0-1 Redis 실패 정책 | `bf4900c` | `RedisGuard` fail-open(레이트리밋·로그인 카운터·캐시) / fail-closed(멱등성 → 503+Retry-After). 카운터 `redis_command_failed_total{op,policy}`. Lettuce 타임아웃 200ms. 단위 테스트 27건 |
| P0-2 HTTP 타임아웃 + slow-call CB | `557c832` `5e34eb0` | api RestClient 3곳·RestTemplate 1곳·HttpRequest 1곳, worker HttpRequest 8곳. CB 9개 전부 `slowCallRateThreshold`. **매달리는 서버를 띄워 타임아웃 발동을 실측**하는 테스트 포함 |
| P0-3 Alertmanager 채널 | `92f8b9a` | `SLACK_WEBHOOK_URL` → sed 렌더링, critical/warning 채널 분리. 양쪽 모드 컨테이너 기동 확인. **웹훅 발급은 사람 몫** — [human-action-items §3](human-action-items.md) |
| P0-4 `X-Bench` 우회 | `bf4900c` | `app.rate-limit.bench-bypass-enabled` 기본 false, local/dev만 true |
| P0-5 백업 스케줄 + 리허설 | `8845fee` | CronJob 매일 03:15 KST + 주 1회 복원 리허설. 로컬에서 **리허설 PASS**(9 테이블, 캔들 217,051행 일치). S3 버킷은 사람 몫 |
| P0-6 readiness에 db | `c9d2b63`~`d4ceb60` | 4개 서비스. liveness에는 넣지 않음(재시작 루프 방지). yml 파싱 테스트로 고정 |
| P0-7 PDB + topologySpread | `e99d63c` | replica≥2인 6개 배포. `kubectl kustomize` 렌더링 확인 |
| (추가) trading-service replica 1 고정 | `6871c0d` | §E6 정정 — 인메모리 호가창 |

전체 단위 테스트: api 492/492, worker 83/83.

### 3.2 P1 실행 기록 (2026-09-11)

| 항목 | 커밋 | 내용 |
|------|------|------|
| P1-1 K8s 관측 스택 | `dbe01fe` | `infra/monitoring/`을 kustomize 루트로 — 규칙·대시보드·Alertmanager 템플릿이 compose와 **단일 소스**. pod 어노테이션 기반 SD, api·worker 어노테이션 추가(없어서 HTTP 메트릭이 안 잡히고 있었다). Grafana만 `/grafana`로 노출 |
| P1-1 알람 재구성 | `3f7c9e4` `cff8860` | 5개 → **page 8 + ticket 14**. `ServiceDown`을 page에서 뺌(단일 pod는 K8s 몫). HTTP 히스토그램 버킷 노출(p95 알람용). `promtool check rules` 22 OK |
| P1-2 삼켜지는 예외 계측 | `348152a` `8959378` `a97d596` `fd84258` | `candle_flush_failed_total`, `alert_rule_eval_failed_total{ruleType}`(+**룰 단위 격리** — 한 룰의 예외가 나머지를 막던 구조 수정), `dlt_messages_total{topic}` ×3, `search_fallback_total{index}` ×8, `ws_active_connections`, `outbox_pending_total`/`outbox_oldest_age_seconds`/`saga_incomplete_total`. worker 커스텀 Kafka 팩토리에 `MicrometerConsumerListener`(랙 메트릭이 없었다) |
| P1-3 `statement_timeout` | `235d9d3`~`cdced0e` | api 30s / worker 60s / trading 10s / quant 120s. 실제 Postgres에서 취소 발생 확인 |
| P1-4 예약 스케일업 | `b3a0f01` | 08:45 KST minReplicas↑, 15:45 KST 원복. prod 6/3 |

전체 단위 테스트: api 495/495, worker 84/84, quant-engine 6/6(해당 클래스).

**§4.4 카탈로그 중 아직 없는 것**: `ledger_reconciliation_mismatch_total`(ADR-043 구현 시),
`redis_command_duration_seconds`(Lettuce 타임아웃으로 대신함), `external_http_*`(resilience4j
`slow_call_rate`로 대신함), `tick_pipeline_latency`(기존 `tick.latency.*` 타이머가 이미 노출).
**§4.6 대시보드 5종은 아직 기존 2종뿐이다** — 새 메트릭이 실제로 흐르는 걸 본 뒤 만든다.
메트릭 없이 만든 대시보드는 빈 패널이다.

**아직 검증하지 않은 것**: 카오스 실험 CH-01/02/06/08. 관측 스택은 이제 있지만 **실제 클러스터에
적용된 적은 없다**(human-action-items §3의 클러스터 프로비저닝이 선행). 로컬 compose에서는
Redis를 실제로 죽여보는 CH-01을 지금 할 수 있다 — 다음 단계.

---

## 4. 모니터링 설계

### 4.1 현재 상태

| 항목 | 로컬(docker-compose) | 운영(K8s) |
|------|:-------------------:|:---------:|
| Prometheus | ✅ (static_configs) | ❌ **없음** |
| Grafana | ✅ 대시보드 2개 | ❌ 없음 |
| Alertmanager | ✅ 단 receiver 빈 껍데기 | ❌ 없음 |
| Jaeger | ✅ all-in-one | ✅ all-in-one (인메모리) |
| 로그 수집 | ❌ stdout | ❌ stdout |

`prometheus.yml`이 `static_configs`로 docker-compose 호스트명(`api:8080`)을 가리킨다 —
K8s에서는 동작하지 않는다. **운영 환경에 관측 스택 자체가 없다.**

### 4.2 계층 구조

```
[L4] SLO / 에러버짓        ← 경영·릴리스 판단
[L3] 알람 (page / ticket)   ← 사람을 깨우는 것만
[L2] 대시보드              ← 알람이 울린 뒤 원인 좁히기
[L1] 메트릭 · 로그 · 트레이스 ← 근거 데이터
```

**원칙**: 알람은 **증상(사용자가 겪는 것)** 에 걸고, 대시보드는 **원인**을 본다.
"CPU 80%"는 알람이 아니라 대시보드 항목이다. 사용자가 아프지 않으면 깨우지 않는다.

### 4.3 SLI / SLO

[ADR-045](decisions/045-performance-slo-and-verification-harness.md)에서 정의한 4개
워크로드 클래스를 그대로 쓴다.

| 클래스 | SLI | SLO | 에러버짓(30일) |
|--------|-----|-----|--------------|
| 조회 | `/api/**` 성공률 | 99.9% | 43분 |
| 조회 | p95 지연 | < 200ms | — |
| 거래 | 주문 접수 성공률 | 99.95% | 21분 |
| 거래 | **잔고 정합성** | **100%** | **0** |
| 실시간 | 틱 → 브라우저 p99 | < 300ms | — |
| 실시간 | 구독 종목 갱신 누락률 | < 0.1% | — |
| 분석 | 백테스트 완료율 | 99% | — |

**거래 정합성의 에러버짓은 0이다.** 다른 SLO는 소진하면 릴리스를 멈추는 신호지만,
이건 하나만 발생해도 조사 대상이다.

### 4.4 메트릭 카탈로그 — 신규 계측이 필요한 것

현재 없어서 판단할 수 없는 것들. **"이 지표가 없으면 그 장애를 감지할 수 없다"** 기준으로 골랐다.

| 메트릭 | 타입 | 왜 필요한가 | 관련 |
|--------|------|-----------|------|
| `redis_command_failed_total{op}` | counter | Redis 실패 시 fail-open이 발동했는지 | A1 |
| `redis_command_duration_seconds` | histogram | A2(지연 장애) 감지 | A2 |
| `kafka_consumer_lag{topic,group}` | gauge | **스케일 판단의 1차 신호** | A5, C2s |
| `external_http_duration_seconds{target}` | histogram | 느린 외부 API 감지 | B1 |
| `external_http_timeout_total{target}` | counter | 타임아웃 발동 횟수 | B1 |
| `ws_active_connections{pod}` | gauge | 동접 및 pod 편중 | C3s |
| `ws_messages_sent_total`, `ws_conflation_ratio` | counter/gauge | fan-out 효율 | ADR-038 |
| `search_fallback_total{index}` | counter | **ES가 죽은 채 방치되는 것 감지** | A6 |
| `search_index_lag_seconds` | gauge | ES 드리프트 | E5 |
| `ledger_reconciliation_mismatch_total` | counter | **잔고 드리프트 — page 알람** | E2 |
| `candle_flush_failed_total` | counter | 캔들 유실 | E4, ADR-041 |
| `alert_rule_eval_failed_total{ruleType}` | counter | **조용한 룰 사망(E7 전례)** | ADR-044 |
| `saga_incomplete_total{status}` | gauge | Saga 잔류 | E3 |
| `outbox_pending_total`, `outbox_age_seconds` | gauge | Outbox 적체 | A5 |
| `backtest_queue_depth` | gauge | bulkhead 포화 | C4s |
| `dlt_messages_total{topic}` | counter | DLT 유입 = 처리 실패 누적 | A5 |
| `tick_pipeline_latency_seconds{stage}` | histogram | 이미 `LatencyTracker` 존재, Prometheus 노출 필요 | 실시간 SLO |

**공통 규칙**: `catch` 블록에 로그만 남기는 곳은 **전부 카운터를 하나 붙인다.**
E7 같은 사고를 막는 유일한 구조적 방법이다.

### 4.5 알람 설계

현재 5개 → **아래 3계층으로 재구성**한다.

#### Page (사람을 깨움) — 사용자가 지금 아프다

| 알람 | 조건 | for |
|------|------|-----|
| `ApiErrorBudgetBurn` | 5xx 비율 > 1% | 5m |
| `ApiLatencyHigh` | p95 > 500ms | 10m |
| **`LedgerMismatch`** | `ledger_reconciliation_mismatch_total` 증가 | 즉시 |
| `OrderPathDown` | 주문 엔드포인트 5xx > 5% | 2m |
| `BrokerCircuitOpen` | `resilience4j_circuitbreaker_state{name=~"kis\|toss",state="open"}` | 30s |
| `AllReplicasDown` | `up{job=~"monticker-.*"}` 전부 0 | 1m |
| `TickPipelineStalled` | `kafka_consumer_lag > 60s` 또는 틱 유입 0 (장중) | 3m |

#### Ticket (업무시간 대응) — 사용자는 아직 안 아프지만 곧 아프다

`HikariPoolNearExhaustion`, `HighJvmHeapUsage`, `DltMessagesGrowing`,
`OutboxBacklog`, `SearchFallbackSustained`, `CandleFlushFailing`,
`AlertRuleEvalFailing`, `BacktestQueueSaturated`, `RedisLatencyHigh`

#### Dashboard only (알람 아님)

CPU/메모리 사용률, 스크레이프 지연, GC 횟수, 캐시 히트율.

> **`ServiceDown`을 Page에서 뺀 이유**: replica 1개가 죽는 건 K8s가 처리한다.
> 사용자가 아픈 건 **전부 죽었을 때**다. 단일 pod 다운으로 사람을 깨우면
> 알람 피로가 쌓이고, 결국 진짜 알람도 무시된다.
> 이 저장소에는 이미 [빨간 CI로도 머지된 전례](engineering-backlog.md)가 있다 —
> 신뢰를 잃은 신호는 신호가 아니다.

#### 알람 전달 (C2 해결)

```yaml
receivers:
  - name: page
    pagerduty_configs: [{ routing_key: ... }]     # 또는 Slack + 전화
  - name: ticket
    slack_configs: [{ channel: '#monticker-alerts' }]
route:
  routes:
    - match: { severity: critical }
      receiver: page
      repeat_interval: 1h
    - match: { severity: warning }
      receiver: ticket
```

**알람 채널 연결은 P0-3이다.** 지금은 아무 데도 안 간다.

### 4.6 대시보드

| 대시보드 | 대상 | 핵심 패널 |
|---------|------|----------|
| **Service Health** (기본) | 온콜 | SLO 4종 현재값 + 에러버짓 소진율, 5xx/지연 추이, 활성 알람 |
| **Trading** | 거래 담당 | 주문 TPS/성공률, 리스크 거부율, Saga 상태 분포, **대사 결과**, 브로커 CB 상태 |
| **Realtime Pipeline** | 시세 담당 | 틱 유입률, 단계별 지연, 컨슈머 랙, WS 동접/pod, conflation 비율, DLT |
| **Data Stores** | 인프라 | Hikari 풀, Redis 지연/메모리, Timescale chunk/압축, ES 색인 지연 |
| **Capacity** | 주간 리뷰 | 성장 추이, 파티션당 처리량, 저장 용량 예측 |

기존 `api-overview.json` / `tick-pipeline.json`을 Service Health / Realtime Pipeline의
출발점으로 삼는다.

### 4.7 로그 · 트레이스

- **구조화 로그**: 이미 `X-Request-Id`가 MDC에 있다. `userId`, `stockId`, `orderId`를 추가.
  JSON 포맷으로 전환해 Loki/OpenSearch에 적재.
- **트레이싱**: 현재 `sampling.probability: 1.0`(전량) + Jaeger all-in-one(인메모리).
  운영에서는 **꼬리 샘플링**으로 전환 — 에러·느린 요청 100%, 정상 0.1%.
  저장소를 영속 백엔드로 교체.
- **민감정보**: 브로커 appKey/appSecret, JWT, 이메일이 로그·스팬에 들어가지 않는지
  마스킹 규칙을 검증한다(로그 수집을 켜기 **전에**).

---

## 5. 부하 테스트 설계

[ADR-045](decisions/045-performance-slo-and-verification-harness.md)의 결정
(도구 분리, 정합성 우선, 로컬/전용 환경 구분)을 따른다. 여기는 시나리오 상세다.

### 5.1 시나리오

| ID | 이름 | 도구 | 부하 곡선 | 통과 기준 |
|----|------|------|----------|----------|
| **L-01** | `rest-baseline` | k6 | 10 → 50 → 100 VU, 각 3분 | p95 < 200ms, 5xx < 0.1% |
| **L-02** | `ws-fanout` | k6 `ws` + STOMP | 100 → 1,000 → 5,000 conn, 각 5분, 종목 20 구독 | 유실 0, p99 < 300ms, pod 메모리 안정 |
| **L-03** | `tick-storm` | Go 생성기 | 1k → 10k → 50k msg/s, 각 5분 | 랙 < 5s, **캔들 정합성 100%** |
| **L-04** | `market-open` | k6 + Go 동시 | 30초 만에 0 → 목표치 100% (접속·조회·틱 동시) | 5xx < 1%, 자동복구 3분 내 |
| **L-05** | `order-burst` | k6 | 50 → 500 TPS, 5분 | **잔고 오차 0**, 중복 체결 0, Saga 잔류 0 |
| **L-06** | `backtest-flood` | k6 | 동시 100건 제출 | 429로 거절, **주문 경로 p95 무영향** |
| **L-07** | `long-soak` | k6 | 목표치의 40%, 8시간 | 메모리·커넥션 누수 0, 지연 증가 < 10% |

### 5.2 각 시나리오가 검증하는 것

- **L-02**는 ADR-038/039의 **효과 측정 수단**이다. 전(conflation 없음, `/topic/market` 있음)과
  후를 같은 곡선으로 비교한다.
- **L-03**은 ADR-040의 파티션 산정 근거("파티션당 ~2,000 msg/s")를 **실측으로 교체**한다.
- **L-04**는 C1s(개장 스파이크)에 대한 유일한 검증 수단이다. 반응형 HPA가 실제로
  몇 초 늦는지를 여기서 잰다.
- **L-05**와 **L-06**은 성능이 아니라 **격리**를 본다 — bulkhead가 실제로 새는지.

### 5.3 정합성 검증 (부하 테스트의 본체)

각 시나리오 종료 후 **독립 재계산**으로 대조한다.

| 검증 | 방법 | 실패 시 |
|------|------|--------|
| 잔고 | `paper_accounts.cash` vs `SUM(ledger_events.amount)` | **즉시 중단, 원인 규명 전 진행 금지** |
| 캔들 | `candles_1m` vs 생성기가 발행한 틱의 독립 집계 | 유실률 기록, 0이 아니면 실패 |
| 주문 | 접수 = FILLED + CANCELLED + PENDING, 중복 `fills` 0 | 실패 |
| 이벤트 | `stock_events` 중복(dedup 인덱스 우회분) | 실패 |
| Saga | STARTED/COMPENSATING 잔류 0 | 실패 |
| ES | 인덱스 문서 수 vs DB 행 수 (±허용치) | 드리프트 기록 |

### 5.4 실행 절차

```
1. 환경 고정 — 이미지 태그, 데이터 볼륨 스냅샷, 시드 데이터 버전을 기록한다.
2. 워밍업 5분 (JIT, 커넥션 풀, 캐시) — 워밍업 구간은 집계에서 제외한다.
3. 측정 실행.
4. 정합성 검증 (§5.3).
5. 결과를 bench/results/ 에 커밋. compare.sh로 직전 기준선과 대조.
6. SLO 위반 또는 정합성 실패 시 → 원인 규명이 끝날 때까지 다음 작업 착수 금지.
```

### 5.5 환경

| 단계 | 환경 | 목적 |
|------|------|------|
| Phase 0 | 로컬 docker-compose | **상대 비교**(전/후), 정합성, 기능 회귀 |
| Phase 1+ | 전용 K8s (prod 유사) | 절대 수치, SLO 판정, 용량 산정 |

로컬 수치를 절대값으로 인용하지 않는다.

---

## 6. 장애 테스트(카오스) 설계

### 6.1 원칙

1. **가설을 먼저 쓴다.** "Redis를 죽이면 조회는 살아있고 주문만 503이 될 것이다."
   가설 없는 주입은 그냥 사고다.
2. **폭발 반경을 제한한다.** 로컬 → 스테이징 → (프로덕션은 P0 완료 후 논의).
3. **중단 조건(abort)을 미리 정한다.** 조건에 닿으면 즉시 주입을 멈춘다.
4. **정상 상태(steady state)를 먼저 정의한다.** 무엇이 "정상"인지 모르면 깨진 걸 모른다.
5. **관측이 먼저다.** §4의 P0-3(알람 연결)과 P1-1(K8s 스택) 전에는 카오스를 하지 않는다 —
   깨졌는지 알 수 없기 때문이다.

### 6.2 실험 카탈로그

각 실험: **가설 / 주입 / 관측 / 성공 기준 / 중단 조건**.

#### CH-01. Redis 전면 정지 — **최우선**

- **가설**: 조회·로그인은 계속 동작하고(레이트리밋 fail-open), 주문만 503을 반환한다.
- **주입**: `docker compose stop redis` (또는 K8s NetworkPolicy로 차단) 3분
- **관측**: `/api/**` 5xx 비율, `redis_command_failed_total`, 로그인 성공률
- **성공 기준**: 조회 API 성공률 > 99%, 주문은 **명시적 503 + `Retry-After`**
- **중단 조건**: 전체 5xx > 10%
- **현재 예상 결과**: **실패한다.** 모든 API가 500이 된다(§A1). 이 실험이
  P0-1의 완료 판정 기준이다.

#### CH-02. Redis 지연 주입

- **가설**: 200ms 타임아웃이 발동해 요청이 매달리지 않는다.
- **주입**: toxiproxy로 Redis 응답에 2초 지연
- **성공 기준**: p95 < 500ms 유지, Tomcat 활성 스레드 < 80%
- **현재 예상 결과**: **실패**(Lettuce 기본 60초 대기).

#### CH-03. Postgres primary 정지

- **가설**: readiness가 떨어져 LB에서 제외되고, 복구 후 자동 재편입된다.
- **관측**: pod Ready 상태, 5xx, 복구 소요 시간
- **성공 기준**: DB 복구 후 **2분 내 자동 정상화, 수동 개입 없음**
- **중단 조건**: 데이터 손상 징후

#### CH-04. Elasticsearch 정지

- **가설**: 검색이 DB 폴백으로 전환되고 사용자는 결과 품질 저하만 겪는다.
- **성공 기준**: 검색 API 5xx = 0, `search_fallback_total` 증가 관측됨
- **현재 예상 결과**: **통과할 가능성이 높다**(A6 — 잘 설계된 영역).
  단 `search_fallback_total` 메트릭이 없어 "폴백했음"을 증명할 수 없다.

#### CH-05. Kafka 브로커 정지

- **가설**: 시세는 끊기지만 주문 이벤트는 Outbox에 남아 복구 후 자동 재전송된다.
- **관측**: `event_publication`에서 `completion_date IS NULL` 행 수
- **성공 기준**: 브로커 복구 후 5분 내(재전송 주기) **유실 0으로 수렴**
- **이 실험이 [ADR-008](decisions/008-outbox-pattern-spring-modulith.md)의 첫 실증이 된다.**

#### CH-06. 외부 브로커 API 지연 (KIS)

- **가설**: 5초 타임아웃 후 CB가 열리고, 주문은 명확한 실패로 반환된다.
- **주입**: KIS 모의 서버를 30초 지연으로 응답하게 함
- **성공 기준**: 주문 외 API의 p95 무영향, CB OPEN 알람 발생
- **현재 예상 결과**: **실패한다.** 타임아웃이 없고 CB가 느린 호출에 반응하지 않는다
  (§B1, C4/C5). **가장 위험한 실험이자 가장 필요한 실험.**

#### CH-07. Pod 강제 종료 (SIGKILL)

- **가설**: 진행 중이던 캔들 1분이 유실되지만 주문·원장은 손실되지 않는다.
- **주입**: `kubectl delete pod --grace-period=0 --force`
- **성공 기준**: 잔고 정합성 100%, 캔들 유실은 기록만
- **관련**: D2, E4

#### CH-08. 노드 손실

- **가설**: 남은 노드의 replica가 트래픽을 받아 서비스가 유지된다.
- **성공 기준**: 다운타임 < 30초
- **현재 예상 결과**: **PDB·anti-affinity가 없어 replica가 한 노드에 몰려 있으면
  전면 장애**(§D3). P0-7의 판정 기준.

#### CH-09. Kafka 파티션 리밸런스 반복

- **가설**: 컨슈머 재할당 중 주문 이벤트 유실이 없다.
- **주입**: 컨슈머 pod를 30초 간격으로 순차 재시작 5분간
- **성공 기준**: 주문 유실 0, 캔들 유실률 기록

#### CH-10. DB 복제 지연 주입 (Phase 1 이후)

- **가설**: read-your-writes가 필요한 경로는 primary로 라우팅된다.
- **주입**: replica에 5초 지연
- **성공 기준**: 주문 직후 조회에서 stale 데이터가 보이지 않음

#### CH-11. 디스크 가득 참

- **가설**: 저장 실패가 명확한 에러로 드러나고 조용히 유실되지 않는다.
- **주입**: 볼륨을 95%까지 채움
- **현재 예상 결과**: **부분 실패 예상.** `PriceTickDbWriter`류의 `catch { log.debug }`
  패턴이 남아 있는 경로는 조용히 삼킨다(§E7).
- **부수 확인**: Kafka replication slot 미소비 시 WAL 무한 증가
  ([ADR-042](decisions/042-outbox-based-es-indexing.md)에서 지적한 리스크) —
  CDC 도입 시 이 실험이 필수가 된다.

#### CH-12. 인증서/시크릿 만료

- **가설**: 만료 전 알람이 울린다.
- **현재 예상 결과**: **실패.** 만료 감시가 없다.

### 6.3 실행 결과

스크립트: [`bench/chaos/`](../bench/chaos/README.md). 로컬 docker-compose + api `bootRun`(local 프로파일).

#### CH-01 Redis 전면 정지 — **PASS** (2026-09-11, 수정 2건 후)

| 단계 | 검색 | 스크리너(캐시) | 로그인 | 가입 | **주문(멱등성)** | readiness |
|------|------|--------------|-------|------|-----------------|----------|
| 정상 | 200 27ms | 200 16ms | 401* 83ms | 200 | 409** 17ms | UP |
| **Redis 정지** | 200 233ms | 200 631ms | 401* 651ms | **200** 580ms | **503 + Retry-After: 2**, 209ms | **UP** |
| 복구 후 | 200 45ms | 200 66ms | 401* 59ms | — | 409** 61ms | UP |

\* 일부러 틀린 비밀번호(로그인 경로가 살아있는지만 본다). \*\* 브로커 계좌 미연결 — 정상적인 비즈니스 거절.
**MTTR 3초**(Redis 기동 → Lettuce ConnectionWatchdog 재연결 → 주문 경로 정상). 수동 개입 없음.
`redis_command_failed_total`이 op·policy별로 정확히 집계됐다(rate_limit 33, cache_get/put 24, idempotency_get 5(closed) …).

**첫 실행에서 발견해 고친 것 (`44cf413`)** — 단위 테스트는 못 잡았다:
1. 멱등성 503 본문이 `charset=ISO-8859-1`로 나가 한글이 `?`로 깨졌다(서블릿 기본 인코딩).
2. Redis 정지 중 회원가입이 500이었다 — 인증 메일 토큰 저장이 가드 밖에 있었다. fail-open으로 변경.

**부수 관찰**: 스크리너는 Redis 정지 중 631ms — 레이트리밋·캐시 get·캐시 put 세 번의 200ms 타임아웃을
순차로 지불한다. 서비스는 성립하지만, Redis 장애가 길어지면 "Redis 회로 열림" 상태를 두어 세 번
시도하지 않고 바로 건너뛰는 최적화 여지가 있다(Redis용 서킷브레이커). 지금은 하지 않는다.

#### CH-02 Redis 2초 지연 (toxiproxy) — **PASS** (2026-09-11)

| 단계 | 스크리너 | 주문 | 동시 20요청 |
|------|---------|------|-----------|
| 정상 | 200 15ms | 409 14ms | — |
| **2초 지연** | 200 627ms | 503 213ms | **20건 965ms** |
| 복구 | 200 43ms | 409 57ms, MTTR 0s | — |

`spring.data.redis.timeout=200ms`가 발동해 요청이 2초씩 매달리지 않는다. 동시 20요청이 1초 안에
끝났다 — 스레드 고갈 없음. **수정 전이었다면 Lettuce 기본 60초 × 20 = 요청마다 1분씩 매달렸다.**

#### 아직 실행하지 않은 것

CH-03(Postgres 정지), CH-04(ES 정지), CH-06(KIS 지연), CH-08(노드 손실)은 다음 순서다.
CH-06은 KIS 모의 서버를 지연시킬 수단(toxiproxy를 `KIS_BASE_URL` 앞에)이 같은 방식으로 가능하다.
CH-08은 K8s 클러스터가 있어야 한다.

### 6.4 게임데이 운영

| 항목 | 규칙 |
|------|------|
| 주기 | 분기 1회 (Phase 1 이후 월 1회) |
| 시간 | 장 마감 후, 사전 공지 |
| 역할 | 진행자 / 온콜(실제 대응) / 관찰자(기록) |
| 온콜에게 알리지 않는 것 | 어떤 실험을 하는지 — **알람과 런북이 실제로 작동하는지**를 본다 |
| 산출물 | 감지까지 걸린 시간(MTTD), 복구까지(MTTR), 런북 갭 목록 |
| 실패 정의 | 실험이 실패하는 건 성공이다. **알람이 안 울린 것**이 진짜 실패다 |

---

## 7. 실행 로드맵

```
[지금 ~ 1주]  P0-1 ~ P0-7   ← 상용 오픈의 최소 조건. Phase 0보다 먼저.   ✅ 2026-09-11
              └ 완료 판정: CH-01 ✅, CH-02 ✅, CH-06 (다음), CH-08 (클러스터 필요)

[1 ~ 3주]     P1-1 (K8s 관측 스택) + §4.4 메트릭 계측
              └ 이게 되어야 Phase 0의 효과를 측정할 수 있다

[3 ~ 5주]     L-01 ~ L-03 기준선 측정 (ADR-045)
              └ Phase 0 착수

[Phase 0 중]  각 ADR 적용 전후 L-02/L-03 비교
              └ ADR-040의 파티션 가정을 실측으로 교체

[Phase 0 후]  게임데이 1회 (CH-01~CH-09)
              └ 통과 후 Phase 1 착수
```

**의존 관계**: 관측 없이 부하 테스트하면 숫자만 나오고 원인을 모른다.
부하 테스트 없이 카오스하면 정상 상태를 모른다.
**순서를 바꾸지 않는다.**

---

## 8. 런북 (작성 대상)

알람마다 런북이 있어야 한다. 없는 알람은 "누군가 언젠가 보겠지"가 된다.

| 런북 | 대응 알람 | 핵심 내용 |
|------|----------|----------|
| `redis-down.md` | `RedisLatencyHigh` 등 | fail-open 확인, 영향 범위, 복구 후 검증 |
| `ledger-mismatch.md` | **`LedgerMismatch`** | **자동 교정 금지**, 조사 절차, 에스컬레이션 |
| `broker-cb-open.md` | `BrokerCircuitOpen` | 사용자 공지 문구, KIS 상태 확인, 수동 리셋 조건 |
| `tick-stalled.md` | `TickPipelineStalled` | 랙 확인, 파티션/컨슈머 상태, 재시작 순서 |
| `db-failover.md` | `ApiErrorBudgetBurn` + DB | 승격 절차, 애플리케이션 재연결, 데이터 검증 |
| `deploy-rollback.md` | — | 롤백 명령, 마이그레이션 역호환 확인 |

**템플릿**: 증상 / 영향 범위 / 1차 확인 3단계 / 완화 조치 / 근본 원인 조사 / 에스컬레이션 기준.

---

## 9. 착수 전 감사 체크리스트

P0 착수와 함께 돌려야 하는 전수 조사.

| 항목 | 명령 | 왜 |
|------|------|-----|
| 타임아웃 없는 HTTP 클라이언트 | `grep -rn "RestClient.builder()\|RestTemplate()\|HttpClient.newHttpClient" --include=*.kt` | B1과 같은 폭탄이 더 있는지 |
| 삼켜지는 예외 | `grep -rn "catch" -A2 --include=*.kt \| grep -i "log.warn\|log.debug"` | E7 패턴 전수 파악 |
| Redis 직접 호출 (try/catch 없음) | `grep -rn "redis.opsForValue()" --include=*.kt` | A1 범위 확정 |
| 무제한 조회 | `grep -rn "findAllBy" --include=*.kt \| grep -v Pageable` | E2와 같은 것 |
| prod 프로파일의 개발 편의 코드 | `grep -rn "X-Bench\|mock.enabled\|unset" --include=*.kt --include=*.yml` | F5 |
| 로그·스팬의 민감정보 | appKey/secret/token이 toString에 포함되는지 | 로그 수집 켜기 전 필수 |

---

## 10. 이 문서가 답하지 못하는 것

정직하게 남긴다.

- **실제 수치가 없다.** 모든 판정은 코드 근거이고, "얼마나 버티는가"는 §5를 실행해야 안다.
- **비용을 다루지 않았다.** 관측 스택·전용 부하 환경·PagerDuty는 실제 비용이 든다.
- **온콜 체계가 없다.** 알람을 연결해도 받을 사람이 정해져 있지 않으면 의미가 없다 —
  이건 기술이 아니라 운영 조직의 문제이므로 [human-action-items.md](human-action-items.md) 영역이다.
- **규제 요건을 반영하지 않았다.** 금융 서비스의 가용성·감사 요건은
  [legal-review-brief.md](legal-review-brief.md)의 법률 검토 결과에 따라 달라질 수 있다.
