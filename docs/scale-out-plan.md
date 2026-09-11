# monticker — 대규모 트래픽·데이터 대응 아키텍처 전환 계획

> Read this when: 동시접속·틱 처리량·저장 데이터량이 지금의 10~1000배가 된다고 가정하고
> 어디를 먼저 고쳐야 하는지 판단할 때. 현재 구조 설명은 [architecture.md](architecture.md),
> 상용화 체크리스트는 [launch-plan.md](launch-plan.md), 구현 대기열은
> [engineering-backlog.md](engineering-backlog.md)를 본다. 이 문서는 **규모(scale)** 축만 다룬다.

작성일: 2026-09-10 · 기준 커밋: `5d788b2` · 상태: **계획(Proposed)** — 여기의 설계 결정은
착수 시점에 각각 ADR로 승격한다(§11).

> **갱신 (2026-09-10)**: Phase 0 전 항목이 ADR로 확정됐다 —
> [ADR-038](decisions/038-broadcast-consumer-partition-assignment.md),
> [ADR-039](decisions/039-drop-global-market-topic.md),
> [ADR-040](decisions/040-kafka-topic-declaration.md),
> [ADR-041](decisions/041-timescale-hypertable-promotion.md),
> [ADR-042](decisions/042-outbox-based-es-indexing.md),
> [ADR-043](decisions/043-ledger-pagination-and-reconciliation.md),
> [ADR-044](decisions/044-alert-rule-in-memory-index.md),
> [ADR-045](decisions/045-performance-slo-and-verification-harness.md).
> ADR을 쓰면서 이 문서의 판단 네 가지가 바뀌었고, 해당 절에 정정 박스로 표시해뒀다:
> (1) fan-out 전용 티어를 **지금 만들지 않는다**(§6.1.1),
> (2) **원시 틱을 Postgres에 저장하지 않기로 확정**했다(§6.2.1),
> (3) ES dual-write 제거는 CDC가 아니라 **Outbox**로 한다(§6.7),
> (4) **§3.6의 "잔고 = replay" 진단이 틀렸다** — 잔고는 이미 컬럼이고, 진짜 문제는
> 컬럼 잔고와 원장의 드리프트를 감지할 수단이 없다는 것이다(§3.6, §6.3.4).

---

## 0. TL;DR

현재 monticker는 "정확하게 동작하는 단일 셀(single-cell) 아키텍처"다. 규모를 키우면
**용량이 부족해지기 전에 먼저 깨지는 곳이 있다.** 우선순위는 이 순서다:

| # | 병목 | 지금 상태 | 깨지는 시점 | 대응 |
|---|------|----------|------------|------|
| 1 | STOMP 인메모리 브로커 | `enableSimpleBroker` | **replicas ≥ 2 (= 현재 prod)** | 외부 브로커 릴레이 또는 전용 fan-out 티어 |
| 2 | `/topic/market` 전역 브로드캐스트 | 모든 틱 × 모든 클라이언트 | 동접 ~1,000 | 1Hz 집계 스냅샷으로 대체 |
| 3 | Kafka 단일 파티션 | `num.partitions` 미지정(=1) | 틱 ~5k/s | 토픽 선언 + 파티션 128~512 |
| 4 | 틱당 알림 룰 DB 조회 | `SELECT ... WHERE stock_id=?` per tick | 틱 ~2k/s | 인메모리 룰 인덱스 + 변경 전파 |
| 5 | hypertable 미적용 | `init-timescaledb.sql` 미연결 | 틱 누적 ~5억 행 | Flyway/부트스트랩으로 승격 + 압축·보존 정책 |
| 6 | 원장 전체 조회 | `findAllByUserIdOrderByCreatedAtDesc` | 유저당 이벤트 ~10만 | 스냅샷 + 페이징 |
| 7 | 매칭 엔진 단일 인스턴스 | JVM 내 `TreeMap` | 주문 ~2k TPS | stockId 샤딩 + 단일 라이터 |
| 8 | 커넥션 풀 총량 | pod당 Hikari 20 | API pod ~25개 | PgBouncer + 읽기 복제본 |

1~6은 **지금 규모에서도 이미 버그이거나 곧 버그**다(Phase 0). 7~8부터가 진짜 "스케일 작업"이다.

---

## 1. 전제 — 목표 규모 정의

숫자 없는 "대규모"는 설계할 수 없다. 아래 3단계를 기준으로 모든 용량 계산을 한다.

| 지표 | T0 현재(MVP) | T1 초기 상용 | **T2 목표 대규모** | T3 여유(2×T2) |
|------|------------|------------|------------------|--------------|
| 유니버스(종목 수) | 202 | 3,000 (KR 전종목) | **12,000** (KR+US 전종목) | 20,000 |
| 평균 틱 유입 | ~200/s | 3,000/s | **30,000/s** | 60,000/s |
| 피크 틱 유입 (동시호가·급등장) | ~200/s | 15,000/s | **150,000/s** | 300,000/s |
| WS 동시접속 | ~10 | 5,000 | **200,000** | 400,000 |
| DAU | ~100 | 50,000 | **2,000,000** | 4,000,000 |
| REST RPS (평균/피크) | 5 / 50 | 500 / 3,000 | **8,000 / 40,000** | 16,000 / 80,000 |
| 주문 TPS (평균/피크) | <1 | 20 / 200 | **500 / 5,000** | 1,000 / 10,000 |
| 백테스트 동시 실행 | 1 | 20 | **500** | 1,000 |

### 1.1 저장 데이터량 (T2 기준)

| 데이터 | 산식 | 일 증가 | 연 증가(250 거래일) | 압축 후(10~20×) |
|--------|------|--------|-------------------|----------------|
| `price_ticks` † | 30,000/s × 23,400s × ~100B | **~70 GB/day** | ~17.5 TB | 0.9 ~ 1.8 TB |
| `candles_1m` | 12,000 × 390 × ~120B | ~0.6 GB/day | ~150 GB | 10 ~ 15 GB |
| `candles_1d` | 12,000 × ~120B | 1.5 MB/day | ~0.4 GB | 무시 가능 |
| `stock_events` | 12,000 × ~50건 × ~1KB | ~0.6 GB/day | ~150 GB | 15 GB |
| `news_articles` + 매핑 | ~50,000건/day × 3KB | ~0.15 GB/day | ~38 GB | 4 GB |
| `ledger_events` | 500 TPS × 23,400s × 3 이벤트 × ~200B | ~7 GB/day | ~1.75 TB | 파티셔닝 필요 |
| `orders` / `fills` | 500 TPS × 23,400s × ~300B | ~3.5 GB/day | ~0.9 TB | 파티셔닝 필요 |

† **`price_ticks`는 현재 한 행도 쓰이지 않는다** — `PriceTickDbWriter`의 호출부가 0건이다(§3.5).
위 수치는 "원시 틱을 Postgres에 저장한다면"이라는 가정 위의 투영이며, 이 가정 자체를
[ADR-041](decisions/041-timescale-hypertable-promotion.md)이 **기각**했다. 표에 남겨둔 이유는
"저장하지 않기로 한 결정이 얼마나 큰 비용을 피한 것인지"를 보여주기 위해서다.

**결론 1**: 원시 틱을 OLTP와 같은 클러스터에 무기한 보관하는 설계는 불가능하다 →
저장하지 않거나(ADR-041 채택), 저장한다면 오브젝트 스토리지 티어링이 **필수**.

**결론 2**: 원장·주문은 압축이 아니라 **시간 파티셔닝 + 아카이브**로 다뤄야 한다.
금융 원장은 삭제할 수 없다(§6.3.3).

### 1.2 WebSocket fan-out 산정 — 가장 과소평가된 축

동접 200,000 × 구독 종목 평균 20 = **400만 구독 관계**.
인기 종목 상위 20개에 구독이 집중된다(파레토 가정: 상위 1%가 구독의 60%).

- 삼성전자 하나에 60,000 구독자, 초당 20틱 → **1.2M msg/s** (단일 종목)
- 전역 `/topic/market`(현재 코드)은 여기에 더해 **모든 틱 × 모든 클라이언트**
  = 30,000 × 200,000 = **6×10⁹ msg/s** → 물리적으로 불가능

→ fan-out은 **conflation(합쳐 보내기)** 없이는 성립하지 않는다(§6.1.3).

---

## 2. 현재 아키텍처 (As-Is) 요약

```
Go market-gateway ─┐
                   ├─► Kafka market.ticks (파티션 1) ─┬─► worker-event (monticker-worker)
MockPriceGenerator ┘                                   │     └ RedisTickWriter / CandleAggregator / EventDetector
                                                       └─► backend/api (monticker-api-broadcast)
                                                             └ PriceBroadcaster → SimpleBroker → STOMP
                                                             └ ConditionalOrderEvaluator (@Async)

backend/api (replicas 2, HPA 2~6)  ── strangler-fig proxy ──► quant-engine :8082
                                                            └► trading-service :8083
공유 인프라: TimescaleDB(단일) · Redis(단일) · Elasticsearch(single-node) · MongoDB(룰셋)
```

특징:
- 모든 서비스가 **하나의 Postgres**를 공유한다(모듈 경계는 코드 레벨에만 존재).
- 상태를 가진 컴포넌트가 프로세스 메모리에 있다: STOMP SimpleBroker, `CandleAggregator.state`,
  `NewsBloomFilter`, `MatchingEngine`의 `TreeMap` OrderBook, `EventDetector`의 EMA 상태.
- 수평 확장은 "무상태 API pod"만 전제로 설계돼 있는데, **위 컴포넌트들이 그 전제를 깬다.**

---

## 3. 병목 인벤토리 (근거 포함)

각 항목은 `파일:라인` 근거 → 깨지는 임계 → 증상 → 대응 순으로 적는다.

### 3.1 [치명·현재진행형] STOMP 인메모리 브로커 + 컨슈머 그룹 분할

- 근거: [WebSocketConfig.kt:24](../backend/api/src/main/kotlin/com/monticker/api/common/config/WebSocketConfig.kt#L24) `registry.enableSimpleBroker("/topic")`
- 근거: [MarketTickBroadcastConsumer.kt:41](../backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/MarketTickBroadcastConsumer.kt#L41) `groupId = "monticker-api-broadcast"`
- 근거: [infra/k8s/base/api.yaml:9](../infra/k8s/base/api.yaml#L9) `replicas: 2`, prod overlay는 `minReplicas: 3`

**임계: replicas ≥ 2 — 즉 지금 prod 설정에서 이미 깨진다.**
같은 컨슈머 그룹의 API pod들이 `market.ticks` 파티션을 **나눠 갖는다**. pod A가 파티션 0을
소비하고 pod B가 파티션 1을 소비하면, pod B에 붙은 클라이언트는 파티션 0 종목의 틱을
**영원히 받지 못한다**. SimpleBroker는 pod 로컬이라 다른 pod로 전파되지 않기 때문이다.

> 지금 파티션이 1개(§3.3)라서 "한 pod만 전부 받고 나머지는 아무것도 못 받는" 형태로
> 나타난다. 즉 **동일 종목을 보는 두 사용자가 서로 다른 실시간성을 경험**한다.
> 파티션을 늘리면(그 자체는 필요한 작업) 이 버그는 오히려 더 넓게 퍼진다 —
> 반드시 §6.1과 함께 처리해야 한다.

대응: §6.1.1 (fan-out 티어 분리) — 순서상 **가장 먼저**.

### 3.2 [치명] `/topic/market` 전역 브로드캐스트

- 근거: [PriceBroadcaster.kt:21](../backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/PriceBroadcaster.kt#L21)
  `messagingTemplate.convertAndSend("/topic/market", message)`

모든 틱이 종목별 토픽과 **전역 토픽 양쪽**에 발행된다. 전역 토픽 구독자는 유니버스 전체의
틱을 받는다. T1(동접 5,000 · 3,000 tick/s)만 돼도 15M msg/s → 브로커·네트워크·클라이언트
모두 붕괴한다. `apps/web`의 `MarketSummary`가 "잡히는 종목을 최근 12개로 제한"하는 식으로
클라이언트에서 방어하고 있다는 것 자체가 이 설계가 잘못됐다는 신호다.

대응: §6.1.3 — `/topic/market`을 삭제하고, 1Hz 주기로 **집계된 시장 스냅샷**을 발행하는
별도 경로로 대체한다.

### 3.3 [치명] Kafka 토픽 파티션 1개 · 토픽 선언 없음

- 근거: `docker-compose.yml:173` `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`,
  `KAFKA_CFG_NUM_PARTITIONS` 미설정 → 브로커 기본값 `num.partitions=1`
- 근거: 코드베이스 전체에 `NewTopic` 빈이 없다 (`grep -rn "NewTopic"` → 0건)

파티션 1개 = **컨슈머 병렬성 1**. `ConcurrentKafkaListenerContainerFactory`의 concurrency를
올려도 소용없다. 단일 파티션의 실질 처리량은 브로커 1대·복제 없음 기준 수천 msg/s 수준이며,
T1(3,000/s)에서 이미 랙이 쌓이기 시작한다.

추가로 auto-create에 의존하면 **파티션 수·복제 계수·retention을 아무도 소유하지 않는다.**
운영 환경에서 토픽이 잘못된 설정으로 생성되면 되돌리려면 재생성(=데이터 손실)이 필요하다.

대응: §6.5.

### 3.4 [높음] 틱마다 알림 룰을 DB에서 조회

- 근거: [AlertEvaluator.kt:66](../backend/worker/src/main/kotlin/com/monticker/worker/alert/AlertEvaluator.kt#L66)
  `fetchRulesForStock(stockId)` → `SELECT ... FROM alert_rules WHERE stock_id = ? AND is_active = true`

처리된 틱 1건당 Postgres 쿼리 1건이다. T2 기준 **30,000 SELECT/s**. 인덱스가 있어도
커넥션·플래너·네트워크 왕복 비용이 그대로 나간다. `@Async("alertDispatchExecutor")`라
스레드풀이 포화되면 알림이 조용히 지연되거나 큐에서 밀린다.

> **추가 발견 (ADR-044 작성 중)**: 더 비싼 경로가 따로 있다. `VOLUME_SURGE` 룰은
> 평가할 때마다 `candles_1d`에 **20일치 AVG 집계 서브쿼리 2개**를 돌린다 —
> 틱 × 해당 종목의 VOLUME_SURGE 룰 수만큼이다. 그런데 `avg_vol`은 확정된 과거 거래일
> 평균이라 **장중에 변하지 않는다**. 캐시 문제가 아니라 계산 위치가 잘못된 문제다.

대응: §6.10 — 룰을 워커 메모리에 종목별 정렬 인덱스로 상주시키고, `avg_vol`은 장 시작 전
배치 1회로 옮긴다. 변경 전파는 Redis pub/sub
([ADR-044](decisions/044-alert-rule-in-memory-index.md)).

### 3.5 [높음] TimescaleDB 기능이 전부 미가동

- 근거: `infra/docker/init-timescaledb.sql`이 `docker-compose.yml`·Makefile·CI 어디에도
  연결돼 있지 않다 (repo 내 유일한 참조는 문서 3곳뿐).
  [ADR-021](decisions/021-candles-1d-realtime-upsert.md#L92)과
  [timescaledb-candle-pipeline.md](technical/timescaledb-candle-pipeline.md#L27)이 이미 이 사실을 기록하고 있다.

따라서 현재 `price_ticks` / `candles_1m` / `candles_1d`는 **평범한 Postgres 테이블**이다:
chunk pruning 없음, 압축 없음, 보존 정책 없음, CAgg는 생성 조건(`IF EXISTS hypertable`)에
걸려 만들어지지도 않는다.

> **추가 발견 (ADR-041 작성 중)**: `PriceTickDbWriter`는 `@Component`로 등록돼 있지만
> **호출부가 0건**이고, `price_ticks`를 읽는 코드도 0건이다 — 이 테이블은 지금까지 단 한 행도
> 쓰인 적이 없다. `LatencyTracker.recordDbWrite()`도 마찬가지라 `/api/latency`의 `dbWrite`
> 단계는 항상 비어 있다. 그리고 CAgg는 `price_ticks`에서 집계하므로, 하이퍼테이블 전환이
> 됐더라도 소스 데이터가 없어 비어 있었을 것이다 — 조건과 데이터 양쪽으로 이중으로 죽어 있었다.
> → [ADR-041](decisions/041-timescale-hypertable-promotion.md)이 캔들만 승격하고 `price_ticks`와
> 죽은 코드를 제거하기로 결정했다.

추가로: hypertable로 전환한 뒤에는 **압축된 chunk를 UPDATE할 수 없다.**
[CandleAggregator.upsertCandle](../backend/worker/src/main/kotlin/com/monticker/worker/marketdata/CandleAggregator.kt#L83)의
`ON CONFLICT DO UPDATE`가 압축 chunk에 닿는 순간 실패한다 → 압축 정책의 `compress_after`는
반드시 "더 이상 upsert가 오지 않는 시간"보다 뒤여야 한다(§6.2.2).

### 3.6 [높음] 원장·이력 전체 조회 (unbounded query)

- 근거: [LedgerEventRepository.kt:8](../backend/api/src/main/kotlin/com/monticker/api/wallet/infrastructure/LedgerEventRepository.kt#L8)
  `findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<LedgerEvent>` — LIMIT 없음
- 근거: [ADR-013](decisions/013-append-only-ledger-wallet.md) "잔고 = 이벤트 replay 합산"

활성 유저의 원장이 10만 건이면 지갑 화면 한 번에 10만 행을 JPA 엔티티로 힙에 올린다.
동시 100명이면 OOM. 특히 `WalletService.getWalletMap`은 **10줄을 보여주려고 전체를 읽고
`.take(10)`** 한다.

> **진단 정정 (ADR-043 작성 중)**: 위 두 번째 근거("잔고 = 이벤트 replay 합산")는
> **ADR-013의 서술을 그대로 옮긴 것이고, 코드는 그렇게 동작하지 않는다.**
> 현금 잔고의 authoritative source는 `paper_accounts.cash` **컬럼**이고
> (`PaperAccountQueryService.getCashBalance`), `ledger_events.balance_after`는 비정규화
> 복사본이며 `ReceiptService`만 읽는다. 즉 **잔고 계산은 이미 O(1)**이고, 문서가 서술한
> 이벤트 소싱은 구현된 적이 없다.
> 따라서 실제 결함은 (1) 표시용 조회의 unbounded 쿼리, (2) **컬럼 잔고와 원장이 어긋나도
> 감지할 수단이 없음** 두 가지다. 후자가 실제 자금이 걸리면 더 중요하다.

대응: §6.3.4 — 커서 페이징 + 대사(reconciliation)용 스냅샷
([ADR-043](decisions/043-ledger-pagination-and-reconciliation.md)).

### 3.7 [높음] 매칭 엔진 단일 인스턴스 · 상태가 힙에 있음

- 근거: [architecture.md](architecture.md) "Order Book 자료구조" — `TreeMap` in-JVM
- 근거: trading-service는 K8s에서 단일 Deployment

OrderBook이 프로세스 힙에 있으므로 trading-service는 **수평 확장이 불가능**하다
(replicas를 늘리면 종목별 호가창이 pod마다 갈라진다 = 체결 정합성 붕괴).
T2 피크 5,000 TPS를 단일 JVM + 단일 Postgres 트랜잭션으로 처리해야 한다.

대응: §6.8 — stockId 해시 샤딩 + 샤드당 단일 라이터 + Kafka 파티션 어피니티.

### 3.8 [중간] 커넥션 풀 총량과 Postgres `max_connections`

- 근거: `application.yml` `hikari.maximum-pool-size: 20`, `connection-timeout: 3000`

pod당 20 커넥션. API(HPA max 6) + worker 3종 + quant-engine + trading-service = 현재도
최대 ~200 커넥션. T2에서 API를 40 pod로 늘리면 **800+ 커넥션** → 일반적인 Postgres
`max_connections`(100~500)를 초과하고, 초과하지 않더라도 커넥션당 백엔드 프로세스 비용으로
DB CPU가 잠식된다. `connection-timeout: 3000`이라 풀이 마르면 3초 뒤 대량 500이 터진다.

대응: §6.3.1 — PgBouncer(transaction pooling) + 읽기 복제본 라우팅.

### 3.9 [중간] 캔들 집계 상태가 컨슈머 메모리에 있음

- 근거: [CandleAggregator.kt:37](../backend/worker/src/main/kotlin/com/monticker/worker/marketdata/CandleAggregator.kt#L37)
  `ConcurrentHashMap<Long, CandleState>`

파티션 리밸런스·pod 재시작 시 진행 중인 분봉이 유실된다(`@PreDestroy` flush는 graceful
종료에서만 동작하고, OOMKill·노드 장애에서는 안 된다). 파티션 수를 늘리고 컨슈머를
여러 pod로 확장하면 **같은 종목이 다른 pod로 이동할 때마다** 이 문제가 발생한다.

대응: §6.2.3 — 키 기반 파티셔닝을 보장하고 CAgg를 진실의 원천으로 삼거나, 상태를
Kafka Streams state store / RocksDB로 외부화한다.

### 3.10 [중간] Dual-write로 유지되는 Elasticsearch

- 근거: [elasticsearch.md](elasticsearch.md) "DB 저장 성공 후 ES 인덱싱. ES 실패는 WARN 로그만"

쓰기량이 커지면 실패율 × 쓰기량 = 무시할 수 없는 드리프트가 된다. `@PostConstruct`
전체 재동기화도 인덱스가 수천만 건이 되면 기동 시간이 감당 안 된다.

> **추가 확인 (ADR-042 작성 중)**: 이건 용량 문제이기 이전에 **지금도 정확성 문제**다.
> [`WatchlistService`](../backend/api/src/main/kotlin/com/monticker/api/watchlist/application/WatchlistService.kt#L52)는
> 클래스 레벨 `@Transactional`인데 `indexToEs`를 **커밋 전에** 호출한다 — 롤백되면 ES에
> 유령 문서가 남는다. dual-write 호출부는 api·worker 두 서비스에 걸쳐 **6곳**이고,
> `news_articles` 인덱스는 **두 개의 독립적인 `NewsDocument` 클래스**(worker/api)가
> 각자 매핑을 정의한다 — 한쪽만 필드를 추가하면 조용히 어긋난다. 6곳 모두 실패를
> `log.warn`으로 삼켜 드리프트를 감지할 메트릭이 없다.

대응: §6.7 — Outbox 기반 단방향 인덱싱 파이프라인
([ADR-042](decisions/042-outbox-based-es-indexing.md)).

### 3.11 [중간] 인메모리 Bloom Filter (뉴스 중복 제거)

- 근거: [ADR-010](decisions/010-bloom-filter-news-deduplication.md), 용량 2,000,000 URL
- 이미 문서에 "레플리카 다중화 시 Redis `BF.ADD`로 교체 가능"이라 적혀 있다.

T2 유니버스(12,000종목)면 용량 가정(5,000종목 × 400 URL)을 넘어 FPP가 1%를 크게 초과한다
= 뉴스 누락 증가. `@DistributedLock`으로 단일 인스턴스를 강제하는 현재 방식은 수집기의
수평 확장을 막는다.

대응: §6.6.

### 3.12 [중간] 스크리너 캐시 스탬피드

- 근거: [CacheConfig.kt](../backend/api/src/main/kotlin/com/monticker/api/common/cache/CacheConfig.kt) `SCREENER` TTL 5초
- 근거: [ScreenerService.kt:22](../backend/api/src/main/kotlin/com/monticker/api/screener/application/ScreenerService.kt#L22)
  키 = `tab:market:sort:limit:offset:marketCapTier`

TTL 5초 + 파라미터 조합 폭발(탭 × 시장 × 정렬 × 페이지 × 시총구간 = 수백 키). 만료 순간
같은 키로 들어온 요청이 전부 DB로 내려간다(스탬피드). 홈 화면 진입 트래픽이 몰리는
개장 직후가 가장 위험하다.

대응: §6.4.3 — Redis ZSET 사전 계산 랭킹 + 요청 병합(single-flight) + probabilistic early
expiration.

### 3.13 [낮음·구조적] 단일 Postgres에 모든 도메인이 공존

원장·주문·시세·뉴스·퀀트가 한 클러스터에 있다. 백테스트 대량 스캔이 주문 처리 트랜잭션의
버퍼 캐시를 밀어낸다(noisy neighbor). T2에서는 도메인별 물리 분리가 필요하다(§6.3.2).

### 3.14 [낮음] 관측성 — Jaeger all-in-one

- 근거: [infra/k8s/base/jaeger.yaml](../infra/k8s/base/jaeger.yaml) all-in-one 이미지

인메모리 스팬 저장. 트래픽이 커지면 샘플링 없이는 붕괴하고, 재시작 시 전부 사라진다.

---

## 4. 목표 아키텍처 (To-Be)

```
                         ┌──────────── CDN (정적·ISR) ────────────┐
Client ──► Global LB ──► │  Edge: WAF · TLS · rate limit          │
                         └───┬───────────────────┬────────────────┘
                             │ REST              │ WebSocket
                    ┌────────▼────────┐   ┌──────▼────────────────────┐
                    │  api (stateless)│   │  fanout-gateway (Go/Netty)│
                    │  HPA 3~60       │   │  StatefulSet, 셀당 N pod  │
                    │  읽기: replica  │   │  구독 레지스트리 = 로컬    │
                    └───┬──────┬──────┘   │  conflation 4~10Hz        │
                        │      │          └──────▲────────────────────┘
             ┌──────────┘      └──────────┐      │ 구독 키로 파티션 어피니티
             ▼                            ▼      │
     ┌───────────────┐            ┌──────────────┴──────┐
     │ trading-shard │◄── Kafka ──┤ Kafka (파티션 256)   │◄── ingest-gateway (Go)
     │  0..N (단일   │  order.*   │ market.ticks(키=stock)│    브로커 WS 멀티플렉싱
     │  라이터/샤드) │            │ market.candles        │
     └───────┬───────┘            │ market.events         │
             │                    └──────────┬────────────┘
             │                               │
     ┌───────▼────────┐   ┌──────────────────▼─────────┐   ┌──────────────┐
     │ orders-db      │   │ tsdb (Timescale, 압축·티어) │   │ Redis Cluster│
     │ (샤딩 · 파티션)│   │ price_ticks / candles_*      │   │ 시세·랭킹·락 │
     └────────────────┘   └──────────────┬──────────────┘   └──────────────┘
                                          │ 90일 초과
                                   ┌──────▼──────┐      ┌────────────────┐
                                   │ Object store│      │ ES cluster     │
                                   │ (Parquet)   │      │ ← Outbox 인덱서 │
                                   └─────────────┘      └────────────────┘
```

핵심 원칙 5가지:

1. **상태를 가진 것과 무상태인 것을 물리적으로 분리한다.** API는 완전 무상태,
   fan-out·매칭·집계는 명시적으로 상태를 소유하는 별도 티어.
2. **모든 상태는 "키 → 소유자(파티션/샤드)" 매핑이 결정적이어야 한다.**
   stockId·userId 해시로 라우팅하고, 소유자는 항상 하나.
3. **읽기와 쓰기 경로를 다른 저장소로 분리한다.** OLTP는 쓰기, 조회는 읽기 복제본/
   읽기모델/캐시/ES.
4. **틱은 "저장"이 아니라 "흐름"이다.** 원시 틱의 최종 목적지는 Timescale이 아니라
   오브젝트 스토리지이고, 서비스가 실시간으로 읽는 건 캔들과 Redis 스냅샷뿐이다.
5. **모든 fan-out에는 conflation을 건다.** 사람이 초당 20번 갱신되는 숫자를 읽지 못한다.

---

## 5. 워크로드 클래스 분리

같은 클러스터에서 성격이 다른 워크로드가 섞이면 서로를 망가뜨린다. 4가지로 나눈다.

| 클래스 | 특성 | 격리 수단 | SLO |
|--------|------|----------|-----|
| **실시간 경로** (틱 수집 → fan-out) | 초당 수만, 지연 민감, 유실 허용 | 전용 노드풀·전용 Kafka 토픽·백프레셔 시 드롭 | p99 end-to-end < 300ms |
| **거래 경로** (주문·체결·원장) | 낮은 처리량, **유실 절대 불가**, 강한 정합성 | 전용 DB·전용 pod·bulkhead | p99 < 500ms, 유실 0 |
| **조회 경로** (차트·스크리너·타임라인) | 높은 RPS, 캐시 가능, 약간의 stale 허용 | 읽기 복제본·CDN·Redis | p99 < 200ms |
| **분석 경로** (백테스트·최적화·리포트) | CPU/IO 폭발적, 지연 무관 | 전용 노드풀·작업 큐·쿼터 | 큐 대기 포함 p95 < 60s |

현재는 `backtestExecutor` bulkhead([architecture.md](architecture.md) "Bulkhead — Backtest 격리")
하나만 이 원칙을 지키고 있다. 나머지 3개도 같은 수준으로 격리해야 한다.

---

## 6. 영역별 상세 설계

### 6.1 실시간 시세 — 수집과 fan-out 분리

#### 6.1.1 fan-out 게이트웨이 티어 신설

`backend/api`에서 WebSocket 책임을 떼어낸다. API pod는 REST만 처리하고, 별도
`fanout-gateway`가 STOMP/WS 연결을 소유한다.

```
market.ticks (파티션 256, key=stockId)
      │  모든 gateway pod가 **각자 고유한 groupId**로 구독
      │  (gateway-{podOrdinal}) — 파티션 분할이 아니라 전체 복제 소비
      ▼
fanout-gateway pod
  ├─ 로컬 구독 레지스트리: Map<stockId, Set<Session>>
  ├─ conflation buffer: stockId → 최신 틱 (덮어쓰기)
  └─ flush ticker 100ms → 구독자에게 배치 전송
```

> **결정 반영 ([ADR-038](decisions/038-broadcast-consumer-partition-assignment.md), 2026-09-10)**:
> 아래 B안(전용 티어)은 **Phase 0에서 채택하지 않았다.** ADR 작성 중 확인한 것은, §3.1의
> 정확성 결함이 티어 분리 없이 **파일 1개 변경**(브로드캐스트 컨슈머를 컨슈머 그룹 대신
> 전 파티션 수동 할당으로 전환)으로 고쳐진다는 점이다. [ADR-033](decisions/033-remove-netty-broadcast-gateway.md)이
> 남긴 교훈("클라이언트 없는 게이트웨이를 만들어놓고 방치했다")을 감안하면, 실측된 병목
> 없이 새 서비스를 세우는 건 같은 실수의 반복이다. 따라서 **ADR-033은 유지되고**, 티어
> 분리는 트리거(동접 5,000/pod, 또는 api pod CPU의 30% 이상이 fan-out)가 실측될 때
> 착수한다. 아래 비교표는 그 시점의 판단 근거로 남겨둔다.

두 가지 선택지가 있고, 트리거 도달 시 **B를 권장**한다.

| 방식 | 내용 | 장점 | 단점 |
|------|------|------|------|
| A. 외부 STOMP 브로커 릴레이 | `enableStompBrokerRelay("/topic")` → RabbitMQ/ActiveMQ | Spring 설정 몇 줄, 코드 변경 최소 | 브로커가 새 단일 병목(200k 연결 × 400만 구독), 운영 부담, conflation 불가 |
| **B. 전용 fan-out 티어** | Go 또는 Netty 기반, Kafka를 각자 전량 소비 | 브로커 없음, conflation·바이너리 프레이밍·백프레셔 완전 제어, 수평 확장이 곧 연결 수 확장 | 새 서비스 1개 추가, 직접 구현 |

> B는 [ADR-005](decisions/005-kafka-go-gateway-netty-broadcast.md)가 원래 제안했다가
> [ADR-033](decisions/033-remove-netty-broadcast-gateway.md)로 제거한 broadcast-gateway와
> **목적은 같지만 이유가 다르다.** ADR-033은 "프론트엔드 클라이언트가 한 번도 없었다"는
> 이유로 제거했다. 재도입한다면 프론트엔드 전환을 **같은 PR에서** 끝내야 한다 —
> 클라이언트 없는 게이트웨이를 또 만들면 안 된다. 재도입 시 ADR-033을
> `Superseded by ADR-0NN`으로 갱신한다.

**"각자 전량 소비"의 비용**: gateway pod가 40개면 `market.ticks`를 40번 읽는다.
T2 기준 30,000 msg/s × ~200B × 40 = **240 MB/s** 브로커 아웃바운드. 허용 가능하지만,
더 커지면 gateway를 **종목 샤드**로 나누고 클라이언트를 구독 종목 해시로 라우팅한다
(= 셀 아키텍처, Phase 3).

#### 6.1.2 수집 게이트웨이 (ingest)

`services/market-gateway`(Go)를 실제 브로커 피드용으로 확장한다.

- KIS/Toss WebSocket은 **세션당 구독 종목 수 상한**이 있다 → 종목을 N개 세션으로 나눠
  멀티플렉싱하는 shard manager 필요.
- 재연결 시 구독 복원, 세션별 헬스체크, 시퀀스 갭 감지.
- **정규화 계층**: 브로커별 원시 메시지 → 공통 `Tick` 스키마. 현재는
  [MarketTickBroadcastConsumer.kt](../backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/MarketTickBroadcastConsumer.kt)
  주석이 인정하듯 "스키마 레지스트리 없이 관례로만 동기화"된다 → §6.5.3.
- 백프레셔: Kafka 프로듀서 큐가 차면 **최신 틱만 남기고 드롭**(시세는 최신값만 의미 있다).
  주문·체결 이벤트는 절대 드롭하지 않는다 — 토픽을 물리적으로 분리하는 이유.

#### 6.1.3 Conflation (합쳐 보내기) — 필수

```
목표: 클라이언트 1개당 최대 10 msg/s (종목당 최대 4 msg/s)

per (pod, stockId):
   buffer[stockId] = latestTick        // 덮어쓰기, 큐가 아님
flush every 100ms:
   for stockId in dirtySet:
       payload = delta(buffer[stockId], lastSent[stockId])
       for session in subscribers[stockId]:
           session.enqueue(payload)
   dirtySet.clear()
```

효과(T2, 삼성전자 구독 60,000명 기준):
- 현재 방식: 20 tick/s × 60,000 = **1.2M msg/s**
- conflation 10Hz + 배치: 10 × 60,000 = 600k msg/s → 여기에 **세션당 다종목 배치 병합**을
  더하면 세션당 10 msg/s × 60,000 = **600k msg/s가 아니라 세션 수 × 10 = 2M msg/s 상한**
  … 이 숫자가 여전히 크다 → 그래서 pod당 세션 수를 5,000으로 제한하고 pod를 40개 둔다
  (pod당 50k msg/s = 처리 가능).

추가 절감:
- **바이너리 프레이밍**: 현재 JSON `Map`(~200B) → MessagePack/CBOR 또는 고정 스키마
  바이너리로 ~40B. 대역폭 5×, GC 압력 대폭 감소.
- **델타 전송**: 종목 메타는 구독 시 1회, 이후 `(price, volume, ts)`만.
- **비활성 탭 억제**: 클라이언트가 `document.hidden`이면 구독 해제 또는 1Hz로 강등.
- **가시 영역 구독**: 스크리너 500행 중 화면에 보이는 ~17행만 구독
  (TanStack Virtual이 이미 DOM은 그렇게 하고 있으니 구독도 맞춘다).

#### 6.1.4 `/topic/market` 대체

`/topic/market`을 삭제하고, worker가 1초마다 시장 요약을 계산해 Redis에 쓰고
`/topic/market-summary`로 **초당 1회** 발행한다.

```
worker-market @Scheduled(1s):
   summary = { 지수, 상승/하락 종목수, 거래대금 상위 10, 급등락 상위 10 }
   redis.set("market:summary", json, ttl=5s)
   kafka.send("market.summary", json)
fanout-gateway: market.summary 구독 → /topic/market-summary 로 그대로 릴레이
```

30,000 msg/s → **1 msg/s**. 홈 화면 위젯은 전부 여기서 데이터를 받는다.

### 6.2 시계열 저장 — Timescale 정상화 + 티어링

#### 6.2.1 hypertable 전환을 배포 경로에 편입 (Phase 0)

> **결정 반영 ([ADR-041](decisions/041-timescale-hypertable-promotion.md), 2026-09-10)**:
> 아래 SQL 초안은 `price_ticks`도 함께 승격하고 `add_dimension`으로 공간 파티셔닝을
> 거는 형태였다. ADR 작성 중 `price_ticks`가 **한 번도 쓰인 적 없는 테이블**임을 확인해
> (§3.5), 최종 결정은 **캔들만 승격하고 `price_ticks`·`PriceTickDbWriter`·CAgg를 제거**하는
> 것으로 바뀌었다. 공간 파티셔닝도 단일 노드에서는 chunk 수만 늘리므로 채택하지 않는다.
> 확정된 SQL은 ADR-041을 본다.

`infra/docker/init-timescaledb.sql`을 Flyway 마이그레이션으로 승격한다.
기존 `V10`이 쓰는 "hypertable이면 실행" 방어 패턴을 그대로 유지하되, 순서를 뒤집는다.

```sql
-- V42__promote_market_data_to_hypertables.sql
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
     PERFORM create_hypertable('price_ticks', 'trade_time',
        chunk_time_interval => INTERVAL '1 hour',   -- T2: 시간당 ~3GB
        migrate_data => TRUE, if_not_exists => TRUE);
     PERFORM create_hypertable('candles_1m', 'candle_time',
        chunk_time_interval => INTERVAL '7 days', migrate_data => TRUE, if_not_exists => TRUE);
     PERFORM create_hypertable('candles_1d', 'candle_time',
        chunk_time_interval => INTERVAL '365 days', migrate_data => TRUE, if_not_exists => TRUE);
     -- 종목 차원 파티셔닝: 조회가 항상 stock_id로 좁혀지므로 chunk 수를 줄인다
     PERFORM add_dimension('price_ticks', 'stock_id', number_partitions => 16, if_not_exists => TRUE);
  ELSE
     RAISE NOTICE 'timescaledb extension 없음 — 일반 테이블 유지';
  END IF;
END $$;
```

주의:
- `migrate_data => TRUE`는 기존 행을 chunk로 옮기며 **테이블 전체를 잠근다.** 운영 데이터가
  큰 상태에서 하면 다운타임이 발생한다 → **지금(데이터가 작을 때) 하는 것이 가장 싸다.**
- CI에 "hypertable로 전환됐는지" 검증 테스트를 추가한다. 다시 조용히 미가동 상태로
  돌아가지 않게 하는 유일한 방법이다.

#### 6.2.2 압축 · 보존 · 티어링

```sql
-- 압축: segmentby=stock_id 로 같은 종목 행을 묶어야 압축률이 나온다
ALTER TABLE price_ticks SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'stock_id',
  timescaledb.compress_orderby   = 'trade_time DESC'
);
-- compress_after 는 "upsert가 더 이상 오지 않는 시간"보다 뒤여야 한다 (§3.5)
SELECT add_compression_policy('price_ticks', INTERVAL '2 days');
SELECT add_compression_policy('candles_1m',  INTERVAL '14 days');

-- 보존: 원시 틱은 90일만 온라인 유지
SELECT add_retention_policy('price_ticks', INTERVAL '90 days');
```

| 데이터 | 온라인(hot) | 압축(warm) | 아카이브(cold) | 근거 |
|--------|-----------|-----------|--------------|------|
| `price_ticks` | 2일 | 2~90일 | 90일+ → Parquet on S3 | 틱 단위 재생은 사후 분석용, 실시간 조회 없음 |
| `candles_1m` | 14일 | 14일~2년 | 2년+ → Parquet | 백테스트가 읽는 주 데이터 |
| `candles_1d` | 전체 | 5년+ | — | 작다. 압축만 |
| `stock_events` | 1년 | 1년+ | 3년+ → 아카이브 | 타임라인 조회 범위 |

아카이브 파이프라인: `worker-batch`가 야간에 만료 예정 chunk를 Parquet으로 내보내고
S3(또는 호환 오브젝트 스토리지)에 `s3://monticker-archive/price_ticks/dt=YYYY-MM-DD/stock_bucket=NN/`
형태로 적재한다. 백테스트가 과거 구간을 요청하면 아카이브에서 읽는다(§6.9).

> **Timescale 라이선스 주의**: 압축(columnstore)·CAgg는 OSS(Apache 2 아님, TSL) 범위에
> 포함되지만, 멀티노드/데이터 티어링 자동화는 Timescale Cloud 기능이다. 셀프호스팅이면
> 티어링은 위처럼 **직접 구현**해야 한다. 착수 전 라이선스 검토 필요 —
> [legal-review-brief.md](legal-review-brief.md)에 항목 추가.

#### 6.2.3 캔들 파이프라인 재설계

현재는 워커 메모리 집계 + upsert(§3.9). 목표 구조:

```
market.ticks (key=stockId)
   ├─► [실시간] Redis: HSET candle:1m:{stockId}:{minute} (o,h,l,c,v)  ← 조회는 여기서
   │      TTL 5분. 실시간 차트/스크리너는 Redis만 읽는다.
   └─► [영속] 분 경계에서 Kafka market.candles 로 발행
          └─► worker-candle: 100~1000건 배치 COPY/INSERT → candles_1m
```

바뀌는 점:
- **UPSERT를 배치 INSERT로**: 분 경계에서 확정된 캔들만 1회 INSERT. 압축 chunk와 충돌하지 않음.
- **상태 유실 내성**: Redis에 매 틱 반영하므로 워커가 죽어도 진행 중 캔들이 남는다.
- **`candles_1d` 실시간 upsert 제거**: [ADR-021](decisions/021-candles-1d-realtime-upsert.md)이
  `candles_1d`를 장중 실시간 upsert하는 이유는 스크리너의 전일 종가 조회 때문이다.
  전일 종가는 Redis(`prevclose:{stockId}`, 장 시작 시 1회 적재)로 옮기면 이 요구가 사라진다.
  → **ADR-021을 대체하는 새 ADR이 필요하다**(§11).
- 파티션 키가 `stockId`이므로 같은 종목은 항상 같은 컨슈머로 간다(순서 보장 유지).

### 6.3 관계형 데이터 — 분리·복제·파티셔닝

#### 6.3.1 커넥션 관리 (Phase 1)

```
API pod (Hikari 10) ──┐
worker pod (Hikari 10)├─► PgBouncer (transaction pooling, pool_size=100) ──► Postgres primary
quant pod  (Hikari 5) ─┘                                                  └► Postgres replica ×2
```

- 애플리케이션 풀은 **줄인다**(20 → 10). PgBouncer가 실제 커넥션을 다중화한다.
- transaction pooling 사용 시 **prepared statement 캐시·세션 상태·advisory lock을 못 쓴다.**
  현재 코드에서 `@DistributedLock`은 Redis 기반이라 안전하지만, JDBC 레벨 세션 의존이
  없는지 착수 전 감사 필요.
- `connection-timeout: 3000` → PgBouncer 앞단에서는 큐 대기가 정상이므로 상향(예: 10s)하고,
  대신 **statement_timeout**을 도입해 느린 쿼리를 잘라낸다.

#### 6.3.2 도메인별 DB 분리 (Phase 2)

| 클러스터 | 테이블 | 이유 |
|---------|-------|------|
| `core-db` | users, stocks, watchlists, alerts, news, disclosures, stock_events | 읽기 위주, 복제본 다수 |
| `trading-db` | orders, fills, paper_*, ledger_events, order_sagas, settlements, portfolio_positions | 쓰기 집중, 강한 정합성, 별도 백업/PITR 정책 |
| `tsdb` | price_ticks, candles_* | 시계열 전용, 다른 워크로드와 자원 경쟁 금지 |
| `quant-db` | rule_sets(현 MongoDB), backtest_results, quant_signals, forward_tests | 분석 워크로드, 스캔이 커도 무방 |

분리 순서는 **`tsdb` → `trading-db` → 나머지**. 도메인 간 조인이 필요한 지점은 이미
Spring Modulith 경계([ADR-019](decisions/019-spring-modulith-boundary-conventions.md))로
정리돼 있으므로, 물리 분리의 걸림돌은 "쿼리에서 직접 조인하는 곳"뿐이다 —
착수 전 `grep`으로 크로스 도메인 조인 목록을 뽑아 API 호출 또는 읽기모델로 대체한다.

**CDC가 필요해지는 지점이 여기다.** DB가 갈리면 크로스 DB 읽기모델을 유지해야 하는데,
애플리케이션 이벤트만으로 커버하려면 **모든 쓰기 경로에 발행을 붙여야 한다.**
이 저장소는 `JdbcTemplate` 직접 쓰기가 많아(`WalletService`, `BehaviorScoreService`,
각종 Collector) 누락이 사실상 확정이다. CDC는 쓰기 경로를 몰라도 WAL에서 잡는다 —
[ADR-042](decisions/042-outbox-based-es-indexing.md)가 ES 인덱싱에 대해 CDC를 기각하면서
"DB 물리 분리 시점에는 다시 옳은 답이 된다"고 남긴 Revisit 조건이 이것이다.
따라서 **CDC 도입은 이 작업의 전제 조건**이며, 순서는 `CDC 파이프라인 구축 → DB 분리`다.

#### 6.3.3 시간 파티셔닝 (Phase 2)

`ledger_events` / `orders` / `fills` / `alert_histories`는 월 단위 declarative partitioning.

```sql
-- 예: ledger_events
CREATE TABLE ledger_events (...) PARTITION BY RANGE (created_at);
CREATE TABLE ledger_events_2026_09 PARTITION OF ledger_events
  FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- pg_partman 또는 배치로 미리 생성
```

주의: `ledger_events`는 **삭제하지 않는다**(금융 원장·감사 요구).
오래된 파티션은 `DETACH` 후 아카이브 테이블스페이스/오브젝트 스토리지로 옮긴다.
파티션 키가 `created_at`이므로 PK를 `(id, created_at)` 복합으로 바꿔야 한다 →
**마이그레이션 시 FK 처리 필요**(현재 `paper_trade_id`, `stock_id` FK 존재).

#### 6.3.4 원장 커서 페이징 + 대사 스냅샷 (Phase 0)

확정: [ADR-043](decisions/043-ledger-pagination-and-reconciliation.md).

- `findAllByUserIdOrderByCreatedAtDesc`를 **삭제**하고 커서 페이징으로 교체
  (`WHERE user_id=? AND id < :cursor ORDER BY id DESC LIMIT 50`).
  정렬 키는 `created_at`이 아니라 **`id`** — 같은 트랜잭션에서 만들어진 이벤트끼리
  `created_at`이 동일할 수 있어 안정적 커서가 되지 못한다.
- `WalletService.getWalletMap`은 전체를 읽고 `.take(10)` 하지 않고 **LIMIT 10으로 조회**한다.

```
ledger_snapshots (user_id, as_of_date, ledger_sum, account_cash, last_event_id,
                  PRIMARY KEY (user_id, as_of_date))

일일 대사:
  ledgerSum = snap.ledger_sum + SUM(amount) WHERE id > snap.last_event_id
  if |ledgerSum - paper_accounts.cash| > ε  →  알람 (자동 교정하지 않는다)
```

> **스냅샷의 목적이 §3.6 정정으로 바뀌었다.** replay 가속이 아니다(잔고는 이미 컬럼이다).
> **대사 비용을 O(전체 원장)에서 O(당일 델타)로 낮추는 것**이 목적이다.
> 컬럼 잔고와 병렬 원장이 공존하는 구조에서는 둘이 어긋날 수 있고,
> 실제 자금이 오가는 서비스에서 그걸 확인하지 않는 건 성립하지 않는다.

#### 6.3.5 읽기 복제본 라우팅 (Phase 1)

현재 코드에 `AbstractRoutingDataSource`나 replica 라우팅이 **전혀 없다**
(`grep -rn "replica\|RoutingDataSource"` → 0건).

```kotlin
// common/config/RoutingDataSourceConfig.kt (신규)
class ReplicaRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): Any =
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) "replica" else "primary"
}
```

전제: **조회 서비스에 `@Transactional(readOnly = true)`가 빠짐없이 붙어 있어야 한다.**
착수 전 감사 항목이며, 복제 지연(replication lag) 때문에 "주문 직후 조회"처럼
read-your-writes가 필요한 경로는 명시적으로 primary를 강제해야 한다.

### 6.4 캐시 계층

#### 6.4.1 Redis Cluster 전환 (Phase 1)

단일 Redis → 클러스터 모드(샤드 3+ / 각 replica 1). 키 스키마는 이미 종목·유저 단위로
잘 나뉘어 있어 슬롯 분산에 유리하다. 주의할 점:

- **멀티키 연산**은 같은 슬롯이어야 한다 → 해시태그 도입:
  `stock:price:{KOSPI:005930}` 처럼 `{}`로 슬롯 키를 고정.
- `@DistributedLock`(SETNX)은 클러스터에서도 동작하지만, 페일오버 중 락 중복 획득 가능성이
  있다 → 스케줄러 락은 **멱등한 작업에만** 쓴다는 전제를 문서화(현재 뉴스/공시 수집기는
  멱등하므로 OK).
- Redis Cache(Spring)의 `RedisCacheManager`는 클러스터에서 `KEYS` 기반 evict가 위험하다 →
  캐시 무효화는 키 직접 지정 방식으로만.

#### 6.4.2 다층 캐시

```
L0  브라우저 (HTTP cache-control, stale-while-revalidate)
L1  CDN / edge (종목 메타·정적 차트 이미지·공개 스크리너)
L2  API pod 로컬 Caffeine (종목 메타, 유니버스 목록 — 변경이 드문 것만, TTL 60s)
L3  Redis Cluster (시세·랭킹·세션·레이트리밋)
L4  Postgres replica
L5  Postgres primary
```

L2가 현재 없다. `stocks` 테이블 조회처럼 "변하지 않는데 자주 읽는" 데이터가 매번 Redis
왕복을 하고 있다. Caffeine 도입은 저비용·고효과.

#### 6.4.3 스크리너 랭킹 사전 계산

TTL 캐시(§3.12)를 버리고 **쓰기 시점 계산**으로 바꾼다.

```
worker-market @Scheduled(1s):
   for each (market, sort) 조합:
      ZADD screener:{market}:{sort} score member=stockId    // 전체 갱신
API 조회: ZREVRANGE screener:{market}:{sort} offset offset+limit  → stockId 목록
          → Redis MGET으로 시세 hydrate (DB 접근 0회)
```

- 조합 수는 유한하다(시장 4 × 정렬 6 × 시총구간 4 ≈ 96 ZSET). 1초마다 전량 갱신해도
  12,000 × 96 = 1.15M ZADD/s는 과하므로, **정렬 축별로 갱신 주기를 다르게** 준다
  (거래대금 1s, 등락률 1s, 시총 1h, 밸류에이션 1d).
- 만료가 없으므로 스탬피드가 원천적으로 사라진다.
- DB 쿼리는 워커의 주기 배치 1건으로 수렴한다.

### 6.5 Kafka

#### 6.5.1 토픽을 코드로 선언 (Phase 0)

auto-create를 끄고 `NewTopic` 빈 또는 IaC로 명시한다.

| 토픽 | 파티션 | 복제 | retention | 키 | 비고 |
|------|-------|------|----------|-----|------|
| `market.ticks` | 256 | 3 | 6h | stockId | 최대 처리량, 짧은 보존 |
| `market.candles` | 64 | 3 | 7d | stockId | 영속 배치용 |
| `market.events` | 32 | 3 | 7d | stockId | 이벤트 감지 결과 |
| `market.summary` | 1 | 3 | 1h | — | 1 msg/s |
| `market.tick-processed` | 128 | 3 | 1h | stockId | 알림 파이프라인 |
| `trading.order-*` | 64 | 3 | **30d** | userId | 유실 불가, min.insync.replicas=2 |
| `*-dlt` | 4 | 3 | 30d | — | 수동 재처리 |

파티션 수 산정 근거: 피크 150,000 msg/s ÷ 파티션당 안전 처리량 ~2,000 msg/s(엔드투엔드
처리 포함) ≈ 75 → 성장 여유 3배로 **256**. 파티션은 늘리기는 쉽고 줄이기는 불가능하므로
처음부터 넉넉히 잡되, 과하면 브로커 메타데이터·리밸런스 비용이 커지니 512는 넘기지 않는다.

#### 6.5.2 압축·배치 튜닝

```yaml
spring.kafka.producer:
  compression-type: lz4        # 시세 JSON은 압축률이 높다. 대역폭 3~5× 절감
  batch-size: 65536
  linger-ms: 5                 # 5ms 지연을 주고 배치 효율을 얻는다
  properties:
    max.in.flight.requests.per.connection: 5   # idempotence=true와 함께 순서 보장
spring.kafka.consumer:
  max-poll-records: 500
  fetch-min-bytes: 65536
  fetch-max-wait-ms: 50
```

`linger-ms: 5`는 §5의 실시간 경로 SLO(300ms) 안에서 충분히 감당된다.

#### 6.5.3 스키마 레지스트리 (Phase 1)

현재 `GeneratedTick`(Kotlin) / `Tick`(Go) / `MarketTickMessage`(Kotlin)가 **필드명 관례로만**
동기화된다 — [MarketTickBroadcastConsumer.kt](../backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/MarketTickBroadcastConsumer.kt)
주석이 직접 그렇게 적어놨다. 서비스가 늘고 배포가 비동기가 되면 필드 하나 바꾸는 순간
조용히 틱이 유실된다(현재 `runCatching`이 WARN 로그만 남기고 삼킨다).

→ Confluent Schema Registry + Avro(또는 Protobuf). 호환성 정책 `BACKWARD`.
Go/Kotlin 양쪽에서 스키마를 생성하고, 스키마 파일은 `packages/schemas/`에 둔다.

#### 6.5.4 컨슈머 병렬성

```yaml
# worker
spring.kafka.listener.concurrency: 8   # pod당 8 스레드, pod 8개 → 64 컨슈머 ≤ 256 파티션
```

주의: concurrency > 1이면 `CandleAggregator`의 인메모리 상태가 스레드 간에 갈린다.
§6.2.3의 Redis 기반 재설계가 **선행 조건**이다.

### 6.6 워커 · 배치

- **뉴스 Bloom Filter → Redis Bloom** (`BF.RESERVE monticker:news:urls 0.001 20000000`).
  용량을 2M → 20M으로 올리고 FPP를 1% → 0.1%로 낮춘다. 수집기 수평 확장 가능해지고
  `@DistributedLock`으로 단일 인스턴스를 강제할 이유가 사라진다.
- **수집기 샤딩**: 종목 유니버스를 N개 샤드로 나눠 워커별 담당 범위를 준다
  (`WORKER_SHARD_INDEX` / `WORKER_SHARD_COUNT`). 락 기반 단일 실행보다 처리량이 N배.
- **배치 잡 큐 분리**: 백테스트·리포트는 Kafka `jobs.*` 토픽 또는 전용 큐로 보내고,
  `quant-engine`을 KEDA(Kafka lag 기반)로 오토스케일한다. HPA(CPU)보다 큐 길이가 정확하다.

### 6.7 검색 (Elasticsearch)

- **single-node → 3 마스터 겸 데이터 노드** (또는 전용 마스터 3 + 데이터 N).
- 인덱스 설계: `news_articles` / `stock_events`는 **시간 기반 롤오버**
  (`news-000001`, ILM: hot 7d → warm 30d → delete/archive). 현재는 단일 인덱스라
  샤드가 무한 증가한다.
- `stocks`는 작고 변경이 드물다 → 샤드 1, replica N (읽기 확장).
- **Dual-write 제거 — Outbox 방식으로 통일한다**
  ([ADR-042](decisions/042-outbox-based-es-indexing.md)):

  ```
  도메인 이벤트 @Externalized → event_publication(같은 트랜잭션)
      → Kafka search.index (key = "{index}:{docId}")
      → EsIndexingConsumer → ES Bulk (배치 1,000건 / 1초)
  ```

  ES 장애 시에도 Kafka에 이벤트가 남아 자동 복구되고, `@PostConstruct` 전체 재동기화가
  필요 없어진다(재색인은 오프셋 리셋으로).

  > **정정 (2026-09-10)**: 이 절은 처음에 **Debezium CDC**를 적었다. ADR-042를 쓰면서
  > 실제 코드를 확인한 결과 판단을 뒤집었다. 결정적 이유는 **CDC가 조인된 문서를 만들지
  > 못한다**는 것이다 — `WatchlistItemDocument`는 `watchlist_items` + `watchlist_groups` +
  > `stocks` 3-way 조인 결과라, CDC로 행 변경을 받아도 인덱서가 DB를 다시 조회해야 한다.
  > 즉 CDC를 써도 "변경 감지"만 얻고 "문서 구성"은 여전히 앱 몫이다. 게다가 dual-write
  > 지점이 6곳뿐이라(§3.10) CDC의 고정비(`wal_level=logical`, Kafka Connect 운영,
  > replication slot 미소비 시 WAL 디스크 풀 리스크)를 정당화하지 못한다.
  > **CDC 자체를 버린 건 아니다** — 도메인별 DB 물리 분리(§6.3.2, Phase 2) 시점에는
  > 다시 옳은 답이 된다(ADR-042 Revisit When).
- 대량 색인 시 `refresh_interval: 30s`, replica 0으로 색인 후 replica 복구.

### 6.8 주문 · 체결 — 매칭 엔진 샤딩

가장 조심스러운 영역이다. **실제 사용자 자금**이 걸린다.

```
POST /api/matching/orders
  → api: 리스크 사전 체크(읽기) → order.commands 토픽 발행 (key = stockId)
       └ 응답: 202 Accepted + orderId (클라이언트는 WS로 체결 통보 수신)

order.commands (파티션 64, key=stockId)
  → trading-shard-{0..N} (StatefulSet, 파티션 정적 할당)
       ├ 로컬 OrderBook(TreeMap) — 이 샤드가 담당하는 종목만
       ├ 체결 → order.events 발행 (Outbox, @AFTER_COMMIT — 현행 유지)
       └ trading-db(샤드별 스키마 또는 샤드 키 포함)
```

핵심 규칙:
1. **종목당 라이터는 항상 정확히 하나.** stockId → 파티션 → 샤드 매핑이 결정적이어야 한다.
   Kafka 컨슈머 그룹의 자동 리밸런스에 맡기면 리밸런스 순간 두 pod가 같은 종목을 잡을 수
   있다 → `assign()` 기반 **정적 파티션 할당** + StatefulSet ordinal 사용.
2. **복구**: 샤드가 죽으면 OrderBook을 재구성해야 한다. `orders` 테이블에서 미체결 주문을
   읽어 재적재(현재도 startup 복구 로직이 필요 — 없다면 추가).
3. **주문 접수는 동기 응답을 포기한다.** 202 + WS 통보로 바꾸면 API pod가 매칭 지연에
   묶이지 않는다. 프론트엔드 변경 동반.
4. **현금 예약의 원자성은 그대로 유지.** [architecture.md](architecture.md)가 기록한
   `UPDATE ... WHERE cash >= ?` 단일 문장 패턴은 샤딩 후에도 유효하다
   (계좌는 userId 기준이므로 종목 샤딩과 축이 다르다 → **크로스 샤드 트랜잭션 발생**).
   → 현금 예약은 매칭 이전에 API/계좌 서비스에서 끝내고, 샤드에는 "예약된 주문"만 보낸다.
   실패 시 Saga 보상([ADR-011](decisions/011-order-saga-orchestration.md))이 환불한다.
5. **실브로커 주문(BYOK)은 샤딩 대상이 아니다.** 체결은 증권사가 한다. 여기서 필요한 건
   레이트리밋(증권사 API 쿼터)·재시도·멱등성이며, 사용자별 큐로 직렬화한다.

### 6.9 퀀트 · 백테스트

- **데이터 접근을 아카이브로**: 백테스트가 `candles_1m`을 직접 스캔하면 OLTP를 잡아먹는다.
  Parquet 아카이브(§6.2.2)를 DuckDB/Polars로 읽는 별도 실행기로 옮긴다.
  10년 × 12,000종목 1분봉 = ~120억 행 → Postgres로는 불가, Parquet+DuckDB로는 가능.
- **잡 큐 + 쿼터**: 유저당 동시 백테스트 1개, 플랜별 상한. 현재 `@RateLimited`
  (10회/시간)는 접수 제한일 뿐 실행 자원 제한이 아니다.
- **결과 캐시**: `(ruleSetFingerprint, universe, period, params)` 해시로 결과 캐싱.
  전략 마켓에서 같은 전략을 여러 사람이 조회하면 재계산이 필요 없다.
- **워커 격리**: `quant-engine`은 전용 노드풀(고CPU 인스턴스, spot 가능 — 재실행 가능하므로).

### 6.10 알림

```
[현재] 틱 → SELECT alert_rules WHERE stock_id=? → 평가 → 푸시
[목표] 틱 → 인메모리 룰 인덱스 조회 → 평가 → notify.commands 토픽 → 발송 워커
```

- **룰 인덱스**: `worker-alert` 기동 시 전체 활성 룰 로드 → `Map<stockId, List<Rule>>`.
  변경은 Redis pub/sub `alert:rules:invalidate`로 전 워커에 전파한다.
  룰 CRUD는 초당 수 건 수준이라 CDC를 끌어올 이유가 없다 —
  `AlertService`가 이미 유일한 쓰기 경로이므로 발행 지점도 한 곳이다.
  T2 기준 활성 룰 500만 건이라도 종목당 평균 400건 → 메모리 수백 MB, 조회 O(1).
- **가격 임계값은 정렬 구조로**: 종목당 룰을 임계가 정렬 리스트로 유지하면 틱 가격으로
  이진 탐색해 "이번에 새로 넘긴 룰"만 뽑을 수 있다 → 전체 스캔 제거.
- **발송 분리**: 평가와 발송을 토픽으로 분리해 Expo/이메일 장애가 평가 파이프라인을
  막지 않게 한다(현재는 `@Async` 스레드풀 포화로 전파된다).
- **쿨다운은 이미 Redis 기반**이라 그대로 사용 가능(`alert:cooldown:{ruleId}`).

### 6.11 관측성 · 용량 관리

| 항목 | 현재 | 목표 |
|------|------|------|
| 트레이싱 | Jaeger all-in-one(인메모리) | OTel Collector → Tempo/Jaeger + Cassandra/ES, **꼬리 샘플링**(에러·느린 요청만 100%, 정상 0.1%) |
| 메트릭 | Micrometer → Prometheus | Prometheus + Thanos(장기보관), 서비스별 recording rule |
| 로그 | stdout | Loki/OpenSearch, `requestId`(이미 있음) + `userId` + `stockId` 구조화 |
| 대시보드 | Grafana | **SLO 대시보드**: §5의 4개 워크로드 클래스별 에러버짓 |

반드시 추가할 지표(현재 없음):
- `kafka_consumer_lag{topic,group}` — 스케일 판단의 1차 신호
- `ws_active_connections{pod}`, `ws_messages_sent_total`, `ws_conflation_ratio`
- `hikari_pending_threads`, `pgbouncer_waiting_clients`
- `timescale_chunk_count`, `timescale_compressed_bytes`
- `matching_shard_queue_depth{shard}`

---

## 7. 단계별 로드맵

각 Phase는 **착수 트리거 지표**를 가진다. 지표에 닿기 전에 미리 하지 않는다(과설계 방지).
단, Phase 0은 트리거와 무관하게 **지금 한다** — 이미 버그이기 때문이다.

### Phase 0 — 지금 고쳐야 하는 것 (트리거: 없음, 즉시)

| # | 작업 | 근거 | 규모 |
|---|------|------|------|
| 0.1 | 브로드캐스트 컨슈머 전 파티션 수동 할당 + conflation ([ADR-038](decisions/038-broadcast-consumer-partition-assignment.md)) | §3.1 replicas≥2에서 틱 유실 | S |
| 0.2 | `/topic/market` 제거 → `market.summary` 1Hz ([ADR-039](decisions/039-drop-global-market-topic.md)) | §3.2 | M |
| 0.3 | Kafka 토픽 명시 선언 + auto-create off ([ADR-040](decisions/040-kafka-topic-declaration.md)) — **0.1 배포 이후** | §3.3 | S |
| 0.4 | 캔들 hypertable 승격 + CI 검증 ([ADR-041](decisions/041-timescale-hypertable-promotion.md)) | §3.5, ADR-021이 이미 지적 | M |
| 0.5 | 압축 정책 + `price_ticks`/죽은 코드 제거 ([ADR-041](decisions/041-timescale-hypertable-promotion.md)) | §3.5 | S |
| 0.6 | 원장 커서 페이징 + 대사 스냅샷 ([ADR-043](decisions/043-ledger-pagination-and-reconciliation.md)) | §3.6 | M |
| 0.7 | 알림 룰 인메모리 인덱스 + 평가/발송 분리 ([ADR-044](decisions/044-alert-rule-in-memory-index.md)) | §3.4 | M |
| 0.8 | SLO 정의 + 검증 하네스 확장 ([ADR-045](decisions/045-performance-slo-and-verification-harness.md)) | 이후 모든 판단의 근거 | M |

> 0.8을 먼저 하는 것도 방법이다. **기준선 없이 최적화하면 개선을 증명할 수 없다.**
> `bench/`에 k6 하네스가 이미 있으므로 시나리오만 추가하면 된다.

### Phase 1 — 초기 상용 규모 (트리거: 동접 > 1,000 **또는** 틱 > 2,000/s **또는** DAU > 20,000)

| # | 작업 | §  |
|---|------|----|
| 1.1 | PgBouncer 도입 + Hikari 풀 축소 | 6.3.1 |
| 1.2 | 읽기 복제본 2대 + `ReplicaRoutingDataSource` (+ `readOnly` 감사) | 6.3.5 |
| 1.3 | Redis Cluster 전환 (해시태그 키 스키마 마이그레이션) | 6.4.1 |
| 1.4 | 캔들 파이프라인 Redis화 + 배치 INSERT (ADR-021 대체) | 6.2.3 |
| 1.5 | 스크리너 ZSET 사전 계산 | 6.4.3 |
| 1.6 | Kafka 스키마 레지스트리 | 6.5.3 |
| 1.7 | ES 3노드 + ILM 롤오버 + **Outbox 인덱싱 전환**([ADR-042](decisions/042-outbox-based-es-indexing.md)) | 6.7 |
| 1.8 | Redis Bloom 전환 + 수집기 샤딩 | 6.6 |
| 1.9 | L2 Caffeine 캐시 | 6.4.2 |
| 1.10 | 관측성: 컨슈머 랙·WS 커넥션 메트릭, 꼬리 샘플링 | 6.11 |

### Phase 2 — 대규모 준비 (트리거: 동접 > 20,000 **또는** 틱 > 15,000/s **또는** 주문 > 200 TPS)

| # | 작업 | § |
|---|------|---|
| 2.1 | 도메인별 DB 분리 (tsdb → trading-db 순) | 6.3.2 |
| 2.2 | `ledger_events`/`orders`/`fills` 월 파티셔닝 | 6.3.3 |
| 2.3 | 매칭 엔진 stockId 샤딩 + 주문 접수 202 비동기화 | 6.8 |
| 2.4 | Debezium CDC 도입 — **크로스 DB 읽기모델 용도**(2.1의 전제 조건) | 6.3.2 |
| 2.5 | 틱 아카이브 파이프라인 (Parquet → 오브젝트 스토리지) | 6.2.2 |
| 2.6 | 백테스트 실행기를 DuckDB/Parquet으로 이전 + 잡 큐 | 6.9 |
| 2.7 | CDN + Next.js ISR/edge 캐싱 | 6.4.2 |
| 2.8 | KEDA 기반 오토스케일(Kafka lag) | 6.6 |

### Phase 3 — 셀 아키텍처 / 멀티리전 (트리거: 동접 > 100,000 **또는** 단일 리전 지연 SLO 위반)

- 사용자를 **셀(cell)** 단위로 분할한다. 셀 = {api, fanout-gateway, trading-shard, redis, db}
  한 벌. 셀 간 공유는 시세 스트림(글로벌)과 종목 마스터뿐.
- 라우팅: `userId % cellCount` → 엣지에서 셀로 고정 라우팅(sticky).
- 장애 영향 범위가 셀 하나로 제한된다(blast radius 축소) — 이게 멀티리전보다 먼저 오는 이유.
- 멀티리전은 **거래 도메인의 단일 쓰기 리전**을 유지하고 조회만 지역 복제하는 형태로 시작.
  금융 데이터의 active-active 쓰기는 정합성 비용이 너무 크다.

### Phase 4 — 운영 성숙

- 용량 계획 정례화(주간 성장률 → 3개월 선행 예측), 카오스 실험(파티션 리밸런스·
  Redis 페일오버·복제 지연 주입), 게임데이, 자동 롤백 게이트.

---

## 8. 마이그레이션 리스크와 롤백

| 작업 | 리스크 | 완화 | 롤백 |
|------|-------|------|------|
| hypertable 전환(0.4) | `migrate_data`가 테이블 잠금 → 다운타임 | **데이터가 작은 지금** 실행, 유지보수 창 | hypertable → 일반 테이블 되돌리기는 사실상 불가 → 사전 백업 필수 |
| fan-out 티어(0.1) | 실시간 시세 전면 장애 | 신구 경로 동시 운영 + 클라이언트 피처 플래그, 카나리 5% | 플래그 off → 기존 STOMP 경로 |
| Kafka 파티션 증가(0.3) | 기존 키의 파티션 매핑 변경 → **순서 보장 일시 붕괴** | 장 마감 후 실행, 토픽 신규 생성 후 컷오버 | 신규 토픽이므로 구 토픽으로 되돌리기 |
| Redis Cluster(1.3) | 키 스키마 변경(해시태그) | 이중 쓰기 → 검증 → 컷오버 | 구 인스턴스 유지 |
| DB 분리(2.1) | 크로스 도메인 조인 누락 발견 | 사전 조인 인벤토리 + 읽기모델 선행 | 논리 복제로 역동기화 유지 |
| 매칭 샤딩(2.3) | **자금 정합성** — 이중 체결·유실 | 섀도 모드(실주문 없이 병렬 실행 후 결과 비교) 최소 2주 | 샤드 수 1로 축소 = 현행과 동일 동작 |
| 파티셔닝(2.2) | PK 변경 → FK 깨짐 | FK를 애플리케이션 레벨 검증으로 이전 | 파티션 → 단일 테이블 병합 스크립트 준비 |

**공통 원칙**: 되돌릴 수 없는 변경(hypertable 전환, 파티션 감소, 데이터 삭제)은
반드시 **백업 검증 후** 실행한다. `infra/db/`에 이미 PITR 스크립트가 있으므로
복원 리허설을 사전 조건으로 둔다.

---

## 9. 비용 개략 (T2, 오더 오브 매그니튜드)

정확한 견적이 아니라 **어디에 돈이 몰리는지** 보기 위한 수치다.

| 항목 | 규모 | 월 비용대 |
|------|------|----------|
| fanout-gateway | 40 pod × 2vCPU/4GB | $$ |
| api | 30~60 pod × 1vCPU/2GB | $$ |
| Kafka | 6 브로커 × (8vCPU/32GB/2TB NVMe) | $$$ |
| tsdb (Timescale) | primary 16vCPU/128GB + 8TB, replica ×2 | $$$$ |
| trading-db | 8vCPU/64GB + replica, PITR | $$$ |
| Redis Cluster | 6 노드 × 16GB | $$ |
| ES | 6 노드 × (8vCPU/32GB/1TB) | $$$ |
| quant-engine (spot) | 가변 0~50 pod × 4vCPU | $$ |
| 오브젝트 스토리지 | 누적 5~20TB + 조회 | $ |
| **네트워크 아웃바운드** | WS 200k × 10 msg/s × 40B ≈ **80 MB/s = ~200 TB/월** | **$$$$** |

가장 큰 두 항목은 **시계열 스토리지**와 **아웃바운드 대역폭**이다.
→ §6.1.3의 바이너리 프레이밍·conflation과 §6.2.2의 압축·보존이
단순한 성능 최적화가 아니라 **직접적인 비용 절감 수단**이다. JSON→바이너리만으로
대역폭 비용이 1/5이 된다.

---

## 10. 검증 전략

확정: [ADR-045](decisions/045-performance-slo-and-verification-harness.md)
(워크로드 클래스별 SLO, 도구 분리, 정합성 하네스, 로컬/전용 환경 구분).

기존 `bench/`(k6)를 확장한다. 각 Phase의 완료 조건 = 아래 시나리오 통과.
참고로 `bench/results/`에는 **2026-06-24 smoke 1건**밖에 없다 —
load/stress/spike는 정의만 있고 실행된 적이 없다.

| 시나리오 | 내용 | 통과 기준 |
|---------|------|----------|
| `ws-fanout` | 동접 N개 연결, 각 20종목 구독, 30분 유지 | 메시지 유실 0, p99 전송 지연 < 300ms, pod 메모리 안정 |
| `tick-storm` | 틱 유입 피크 레이트, 10분 | 컨슈머 랙 < 5s, 캔들 정합성 100%(재계산 대조) |
| `market-open` | 개장 직후 스파이크 재현: 접속 폭증 + 틱 폭증 + 스크리너 조회 폭증 동시 | 에러율 < 0.1% |
| `order-burst` | 주문 피크 TPS, 잔고 정합성 검증 | **잔고 오차 0**, 중복 체결 0, Saga 미완료 0 |
| `replica-lag` | 복제 지연 5초 주입 | read-your-writes 경로가 primary로 라우팅되는지 |
| `rebalance` | Kafka 리밸런스·pod kill 반복 | 캔들 유실 0, 주문 유실 0 |
| `long-soak` | 8시간 지속 부하 | 메모리 누수 없음, 커넥션 누수 없음 |

**정합성 검증 하네스가 특히 중요하다.** 부하 중 잔고·캔들·주문을 독립적으로 재계산해
대조하는 스크립트를 만들어야, 성능 최적화가 정확성을 깨지 않았음을 증명할 수 있다.

---

## 11. 필요한 ADR 목록

착수 시 아래 ADR을 작성한다(CLAUDE.md의 ADR 작성 기준에 전부 해당).

**미작성 항목에는 번호를 미리 배정하지 않는다.** ADR 번호는 CLAUDE.md 규칙상
"기존 최대 번호 + 1"로 작성 시점에 정해지므로, 예약해두면 작성 순서가 바뀔 때마다
어긋난다(실제로 ADR-042는 원래 "캔들 파이프라인"으로 예약돼 있었으나 ES 인덱싱이
먼저 작성되면서 그 번호를 가져갔다).

### 작성 완료

| 번호 | 제목 | 대체/갱신 대상 |
|-----|------|--------------|
| [ADR-038](decisions/038-broadcast-consumer-partition-assignment.md) | 브로드캐스트 컨슈머 전 파티션 수동 할당 + conflation | ADR-029 갱신 노트. **ADR-033은 유지**(전용 티어를 지금 만들지 않기로 결정) |
| [ADR-039](decisions/039-drop-global-market-topic.md) | `/topic/market` 폐지 — 시장 요약 1Hz + 가시 종목 구독 | — |
| [ADR-040](decisions/040-kafka-topic-declaration.md) | Kafka 토픽 코드 선언 · auto-create 폐지 · 파티션 설계 | ADR-005/006 보강 (스키마 레지스트리는 Phase 1로 분리) |
| [ADR-041](decisions/041-timescale-hypertable-promotion.md) | 캔들 hypertable 승격·압축, **원시 틱 미저장 확정** | ADR-002 갱신 노트, ADR-021 제약 추가 |
| [ADR-042](decisions/042-outbox-based-es-indexing.md) | ES 인덱싱을 **Outbox 단일 파이프라인**으로 통일, **CDC 미채택** | ADR-008 패턴 확장. §6.7의 Debezium 안을 대체 |
| [ADR-043](decisions/043-ledger-pagination-and-reconciliation.md) | 원장 커서 페이징 + **대사(reconciliation) 스냅샷** | **ADR-013 서술 정정**(잔고=replay는 구현된 적 없음). §3.6 진단 정정 |
| [ADR-044](decisions/044-alert-rule-in-memory-index.md) | 알림 룰 인메모리 인덱스 · `avg_vol` 배치화 · 평가/발송 분리 | ADR-003 관련. ADR-040에 `notify.commands` 토픽 추가 |
| [ADR-045](decisions/045-performance-slo-and-verification-harness.md) | 워크로드별 SLO 정의 + 검증 하네스 범위 (정합성 포함) | — |

### 미작성 (착수 시 번호 부여)

| 제목 | 대체/갱신 대상 | Phase |
|------|--------------|-------|
| 캔들 파이프라인 Redis 상태 + 배치 INSERT | **ADR-021을 Superseded로** | 1 |
| 읽기 복제본 라우팅 · PgBouncer | — | 1 |
| Kafka 스키마 레지스트리 (Avro/Protobuf) | ADR-040 후속 | 1 |
| Redis Bloom 전환 | **ADR-010을 Superseded로** | 1 |
| 도메인별 DB 물리 분리 | ADR-001 보강 | 2 |
| **Debezium CDC 도입 — 크로스 DB 읽기모델** | ADR-042의 Revisit 조건. DB 분리의 전제 조건 | 2 |
| 매칭 엔진 stockId 샤딩 · 주문 접수 비동기화 | ADR-011 보강 | 2 |
| fan-out 전용 티어 분리 | ADR-038의 Revisit 조건 도달 시 | 2~3 |
| 셀 아키텍처 · 사용자 샤딩 | ADR-009 보강 | 3 |

---

## 12. 하지 않을 것 (Non-goals)

명시적으로 **범위 밖**이다. 나중에 논쟁을 반복하지 않기 위해 적어둔다.

- **모든 모듈의 마이크로서비스화.** [ADR-001](decisions/001-modular-monolith.md)의
  모듈러 모놀리스 판단은 여전히 유효하다. 분리는 §5의 워크로드 클래스가 실제로 서로를
  방해할 때만 한다. 조직 규모가 아니라 **자원 경쟁**이 분리의 근거다.
- **거래 데이터의 멀티리전 active-active 쓰기.** 정합성 비용 대비 이득이 없다.
  조회만 지역 복제한다(§Phase 3).
- **Kafka를 Pulsar/Redpanda로 교체.** 현재 Kafka로 안 되는 것이 없다. 파티션 설계와
  튜닝이 안 된 것을 브로커 교체로 해결하려 하면 안 된다.
- **자체 시계열 DB 구현 / Timescale 탈피.** Timescale의 기능을 **아직 하나도 안 켰다**(§3.5).
  켜본 뒤에 판단한다.
- **NoSQL 전면 도입.** 원장·주문은 관계형 + 트랜잭션이 맞다. MongoDB는 룰셋 문서 저장이라는
  현재 용도([V22](../backend/api/src/main/resources/db/migration/V22__migrate_ruleset_to_mongodb.sql))에
  한정한다.
- **GPU/ML 인프라.** AI 주문 제안([ADR-036](decisions/036-ai-order-proposal.md))은 외부
  API 호출이며, 자체 모델 서빙은 이 문서의 범위가 아니다.

---

## 부록 A. 착수 전 감사(audit) 체크리스트

Phase 1~2를 시작하기 전에 코드베이스에서 확인해야 하는 것들. 각각 한 줄 명령으로 시작한다.

| 확인 항목 | 방법 | 왜 |
|----------|------|-----|
| `readOnly` 누락 조회 서비스 | `grep -rn "@Transactional" --include=*.kt \| grep -v readOnly` | 복제본 라우팅(§6.3.5) 전제 |
| 크로스 도메인 조인 | 마이그레이션 SQL·JdbcTemplate 쿼리에서 도메인 경계를 넘는 JOIN | DB 분리(§6.3.2) 전제 |
| JDBC 세션 상태 의존 | advisory lock, temp table, `SET` 문 사용처 | PgBouncer transaction pooling(§6.3.1) 전제 |
| 무제한 조회 | `findAllBy...` 중 `Pageable`/`limit` 없는 것 | §3.6과 같은 폭탄이 더 있는지 |
| 프로세스 메모리 상태 | `ConcurrentHashMap`/`TreeMap` 필드를 가진 `@Component` | 수평 확장 저해 요소 전수 파악 |
| N+1 쿼리 | 루프 안의 `jdbc.query*` 호출 | ReplayService·BehaviorScoreService에 후보 존재 |
| 삼켜지는 예외 | `runCatching`/`catch` 후 WARN·DEBUG만 남기는 곳 | 규모가 커지면 조용한 데이터 유실이 된다 |

## 부록 B. 용어

| 용어 | 뜻 |
|------|-----|
| Conflation | 같은 키의 갱신을 버퍼에서 덮어써 최신값만 주기적으로 보내는 기법 |
| Cell | 독립적으로 완결된 서비스 한 벌. 장애 영향 범위를 가두는 단위 |
| Single writer | 하나의 상태(호가창 등)에 대해 쓰기 주체를 정확히 하나로 제한하는 원칙 |
| Stampede | 캐시 만료 순간 동일 키 요청이 일제히 원본으로 몰리는 현상 |
| CDC | Change Data Capture. DB의 변경 로그(WAL)를 읽어 이벤트로 흘리는 기법 |
| Blast radius | 하나의 장애가 영향을 미치는 범위 |
