# ADR-042: Elasticsearch 인덱싱을 Outbox 기반 단일 파이프라인으로 통일 (CDC는 채택하지 않는다)

## Status
Accepted

## Context

[elasticsearch.md](../elasticsearch.md)가 기록한 현재 원칙은 **dual-write**다:
"DB 저장 성공 후 ES 인덱싱. ES 실패는 `WARN` 로그만 남기고 트랜잭션에 영향 없음."

[scale-out-plan.md](../scale-out-plan.md) §3.10은 이걸 "쓰기량이 커지면 드리프트가 된다"는
용량 문제로만 적었는데, 실제 코드를 확인해보니 **지금도 정확성 문제**가 있다.

### 확인된 사실

**1) 트랜잭션 안에서 ES를 쓴다.**
[`WatchlistService`](../../backend/api/src/main/kotlin/com/monticker/api/watchlist/application/WatchlistService.kt#L52)는
클래스 레벨 `@Transactional`인데 `indexToEs(saved, ...)`가 커밋 **전에** 호출된다.
이후 트랜잭션이 롤백되면 ES에는 존재하지 않는 관심종목 문서가 남는다.
[ADR-008](008-outbox-pattern-spring-modulith.md)이 주문 이벤트에 대해
`@TransactionalEventListener(AFTER_COMMIT)`으로 정확히 이 문제를 막아뒀는데,
ES 인덱싱에는 같은 원칙이 적용되지 않았다.

**2) dual-write 지점이 두 서비스에 흩어져 있다.**

| 서비스 | 호출부 | 대상 인덱스 |
|--------|-------|-----------|
| `backend/api` | `WatchlistService.indexToEs` / `deleteFromEs` | `watchlist_items` |
| `backend/api` | `AlertService` | `alert_histories` |
| `backend/worker` | `NewsCollector.indexToEs` | `news_articles` |
| `backend/worker` | `StockEventWriter.indexToEs` | `stock_events` |
| `backend/worker` | `AlertEvaluator` | `alert_histories` |
| `backend/worker` | `DisclosureCollector` | `stock_events` |

`alert_histories`와 `stock_events`는 **두 서비스가 각각 쓴다.**

**3) 같은 인덱스를 두 개의 독립적인 문서 클래스가 정의한다.**

```
backend/worker/.../news/NewsDocument.kt                @Document(indexName = "news_articles")
backend/api/.../news/infrastructure/NewsDocument.kt    @Document(indexName = "news_articles", createIndex = false)
```

현재는 필드가 일치하지만 **강제하는 장치가 없다.** 한쪽에만 필드를 추가하면 매핑이
조용히 어긋나고, `createIndex = true`인 worker 쪽이 인덱스 생성 권한을 가진다.

**4) 실패가 전부 삼켜진다.** 6개 호출부가 모두 `catch { log.warn(...) }`이다.
드리프트가 쌓여도 감지할 메트릭이 없다. 복구 수단은 `@PostConstruct` 전체 재동기화뿐이라,
인덱스가 커지면 기동 시간이 감당되지 않는다.

**5) PK를 얻으려 쿼리를 한 번 더 한다.**
[`NewsCollector.persist`](../../backend/worker/src/main/kotlin/com/monticker/worker/news/NewsCollector.kt#L99)는
`ON CONFLICT DO NOTHING` + `RETURNING` 부재 때문에 기사 1건마다
`SELECT id FROM news_articles WHERE url = ?`를 추가로 날린다.

### 후보

- **A) 현행 dual-write 유지 + 방어 강화** — `AFTER_COMMIT`으로 옮기고 실패 메트릭을 붙인다.
- **B) Outbox → 단일 인덱싱 컨슈머** — 각 서비스가 도메인 이벤트를 Outbox에 기록하고,
  하나의 컨슈머가 소비해 bulk 색인한다.
- **C) Debezium CDC → 단일 인덱싱 컨슈머** — Postgres WAL을 읽어 변경을 흘린다.

scale-out-plan §6.7은 처음에 **C**를 적었다. 이 ADR은 그 판단을 뒤집는다.

## Decision

**B안** — Outbox 기반 단일 인덱싱 파이프라인으로 통일한다. **CDC는 지금 도입하지 않는다.**

```
[api]    도메인 이벤트 @Externalized("search.index::{index}:{docId}")
[worker] 도메인 이벤트 @Externalized(...)          ← spring-modulith 의존 추가
              │  DB 쓰기와 같은 트랜잭션 (event_publication)
              │  커밋 후 Modulith가 Kafka 발행
              ▼
      Kafka  search.index   (key = "{index}:{docId}", 순서 보장)
              │
              ▼
      EsIndexingConsumer  ← backend/api 안의 @KafkaListener 하나
        ├─ 배치 수집 (최대 1,000건 / 1초)
        ├─ ES Bulk API 1회 호출
        └─ 실패 → @RetryableTopic → search.index-dlt (ADR-006/040)
```

### 1. 6개 dual-write 호출부를 전부 제거한다

각 서비스는 도메인 이벤트만 발행한다. ES를 직접 호출하지 않는다.

```kotlin
// 예: worker/news/NewsCollector
@Externalized("search.index::#{'news_articles:' + #this.docId}")
data class NewsIndexed(val docId: String, val payload: NewsIndexPayload) : SearchIndexEvent

@Externalized("search.index::#{'watchlist_items:' + #this.docId}")
data class WatchlistItemRemoved(val docId: String) : SearchIndexEvent   // 삭제도 이벤트
```

### 2. `backend/worker`에 Spring Modulith 이벤트 발행을 추가한다

worker는 이미 `spring-boot-starter-data-jpa`를 쓰므로
`spring-modulith-starter-jpa` + `spring-modulith-events-kafka` 추가로 끝난다.
`event_publication` 테이블은 [V18](../../backend/api/src/main/resources/db/migration/V18__create_spring_modulith_event_publication.sql)이
이미 만들어뒀고 DB를 공유하므로 새 마이그레이션이 필요 없다.

### 3. 문서 클래스를 하나로 합친다

인덱싱 컨슈머가 유일한 ES writer가 되므로 문서 클래스도 그쪽 한 곳에만 둔다.
worker/api의 중복 정의(`NewsDocument` ×2 등)를 제거한다.
`createIndex`는 인덱서만 `true`를 갖는다.

### 4. 인덱서는 새 서비스가 아니라 `backend/api` 안의 컨슈머로 시작한다

[ADR-033](033-remove-netty-broadcast-gateway.md)의 교훈 — 실측된 병목 없이 새 서비스를
세우지 않는다. 색인이 API 스레드풀을 방해하는 게 실제로 관측되면 그때 분리한다.

### 5. 재색인은 Kafka 오프셋 되감기로 한다

`search.index`의 retention은 7일. 그 안의 재색인은 컨슈머 그룹 오프셋 리셋으로 해결한다.
전체 재색인이 필요하면 기존 `*Indexer`의 전량 동기화 로직을 `@PostConstruct`에서
**관리자 엔드포인트로 옮겨** 수동 실행한다 — 기동 시마다 도는 지금 방식은 인덱스가
커지면 그 자체가 장애 원인이 된다.

### 6. CDC는 DB 물리 분리 시점에 다시 판단한다

Revisit When에 조건을 명시한다.

## Reasons

### CDC가 이 문제에 잘 맞지 않는 이유

- **CDC는 조인된 문서를 만들지 못한다.** 이게 결정적이다.
  `WatchlistItemDocument`는
  [`watchlist_items` + `watchlist_groups` + `stocks` 3-way 조인](../../backend/api/src/main/kotlin/com/monticker/api/watchlist/application/WatchlistIndexer.kt#L38)
  결과다. CDC는 행 단위 변경만 주므로, 문서를 구성하려면 인덱서가 결국 DB를 다시 조회해야
  한다. 즉 CDC를 써도 "변경 감지"만 얻고 "문서 구성"은 여전히 애플리케이션 몫으로 남는다.
  게다가 조인 대상 테이블(`stocks`) 하나가 바뀌면 어떤 문서들을 다시 만들어야 하는지를
  역추적하는 로직까지 새로 필요해진다.
  Outbox는 도메인 이벤트에 **완성된 문서를 그대로 담을 수 있다.**
- **행 diff에서 의도를 역추론해야 한다.** "이 UPDATE가 생성인가 수정인가", 삭제는
  tombstone인가 soft delete인가. 도메인 이벤트에는 이 정보가 처음부터 들어있다.
- **인프라 비용이 크다.** `wal_level=logical`(운영 DB 재기동), Kafka Connect 또는
  Debezium Server 운영, 테이블별 `REPLICA IDENTITY` 관리. 그리고
  **replication slot이 소비되지 않으면 Postgres가 WAL을 무한 보관해 디스크를 채우고 멈춘다** —
  커넥터 장애가 DB 장애로 번지는 경로가 새로 생긴다.
- **dual-write 지점이 6곳뿐이다.** CDC가 이기는 조건은 "테이블이 많고 쓰기 경로를
  전부 찾아 고칠 수 없을 때"다. 지금은 그 조건이 아니다.

### B안이 A안보다 나은 이유

- A안(AFTER_COMMIT + 메트릭)은 롤백 유령 문서는 막지만 **ES 장애 시 유실은 그대로**다.
  이벤트가 어디에도 남지 않으므로 복구는 여전히 전체 재동기화뿐이다.
- 문서 클래스 중복과 인덱스 소유권 부재도 A안으로는 해결되지 않는다.

### B안이 싼 이유

- **새 인프라가 0개다.** Kafka·Modulith·`event_publication` 테이블이 전부 이미 있다.
- **이미 이 저장소에서 검증된 패턴이다.** [ADR-008](008-outbox-pattern-spring-modulith.md)이
  주문 이벤트로 같은 구조를 돌리고 있고, `OutboxResubmissionConfig`가 미완료 이벤트를
  5분마다 재전송하는 안전망까지 있다. ES 인덱싱은 그 안전망을 그대로 물려받는다.
- worker의 진입 비용이 낮다 — JPA가 이미 있어 의존성 2개 추가로 끝난다.

## Consequences

- **ES 반영이 동기에서 비동기가 된다.** 관심종목 추가 직후 검색하면 아직 안 나올 수 있다
  (Kafka 왕복 + bulk 배치 최대 1초 + ES `refresh_interval`). 관심종목·알림 검색은
  read-your-writes가 요구되는 화면이 아니므로 허용 가능하다. **단, 목록 조회는 DB가
  authoritative라는 기존 원칙을 유지**해야 한다 — 방금 추가한 항목이 목록에서 사라져
  보이면 안 된다.
- **`backend/worker`가 Spring Modulith에 의존하게 된다.** 지금은 api만 쓰고 있다.
  `@Externalized` 이벤트가 두 서비스에 생기므로,
  [ADR-019](019-spring-modulith-boundary-conventions.md)의 모듈 경계 규칙이 worker에도
  적용되는지 확인이 필요하다(worker는 모듈 구조가 api와 다르다).
- **`search.index` 토픽이 추가된다.** 파티션·retention은
  [ADR-040](040-kafka-topic-declaration.md)의 표에 행을 추가한다(파티션 16, retention 7d,
  재시도/DLT 토픽 포함).
- **인덱싱 지연이 새 관측 대상이 된다.** `search_index_lag_seconds`(이벤트 발행 →
  ES 색인 완료)와 `search_index_failed_total`을 노출한다. 지금은 실패가 로그로만 남아
  드리프트를 감지할 방법이 없다.
- **`@PostConstruct` 전체 재동기화가 사라진다.** 기동이 빨라지는 대신, ES가 비어 있는
  새 환경에서는 관리자가 재색인을 명시적으로 실행해야 한다. 로컬 개발 편의를 위해
  `app.search.reindex-on-startup=true`(기본 false, dev 프로필만 true) 플래그를 둔다.
- **`NewsCollector`의 추가 `SELECT`가 없어질 수 있다.** `INSERT ... ON CONFLICT DO NOTHING`을
  `RETURNING id`와 함께 쓰거나 이벤트에 URL을 키로 실으면 왕복이 사라진다.
  이 ADR의 필수 범위는 아니지만 같은 작업에서 정리하기 좋다.
- **모든 dual-write를 한 번에 걷어낼 필요는 없다.** 인덱스 단위로 옮길 수 있다
  (`news_articles` → `stock_events` → `watchlist_items` → `alert_histories`).
  전환 중에는 한 인덱스에 두 경로가 동시에 쓰지 않도록, 인덱스 단위로 **완전히** 넘긴다.

## Revisit When

- **도메인별 DB 물리 분리를 실행할 때**(scale-out-plan §6.3.2, Phase 2) — `trading-db`와
  `core-db`가 갈리면 크로스 DB 읽기모델이 필요해지고, 그때는 **모든 쓰기 경로에 이벤트
  발행을 붙여야 한다.** 이 저장소는 `JdbcTemplate` 직접 쓰기가 많아(`WalletService`,
  `BehaviorScoreService`, 각종 Collector) 누락이 사실상 확정이다. CDC는 쓰기 경로를 몰라도
  WAL에서 잡으므로 **그 시점에는 CDC가 옳은 답이 된다.** 이 ADR은 "CDC를 영원히 쓰지
  않는다"가 아니라 "ES 인덱싱만을 위해 지금 도입하지 않는다"이다.
- **앱과 독립적인 감사 추적이 규제 요건이 될 때** — 앱이 남기는 감사 로그는 앱이 정직할
  때만 정확하다. 실브로커 주문([ADR-025](025-real-brokerage-order-safety-gate.md))이나
  원장([ADR-013](013-append-only-ledger-wallet.md))에 대해 "앱 버그로 인한 잘못된 UPDATE도
  잡아야 한다"는 요구가 생기면 CDC가 필요하다.
- **인덱싱 컨슈머가 API 스레드풀·GC에 영향을 준다는 게 실측될 때** — 별도 `es-indexer`
  서비스로 분리한다(Decision 4).
- **비검색 목적의 파생 데이터가 늘어날 때** — 지금은 ES 하나지만, 분석계 적재·데이터
  웨어하우스·실시간 집계가 추가되면 소비자 수가 CDC의 고정비를 정당화할 수 있다.
