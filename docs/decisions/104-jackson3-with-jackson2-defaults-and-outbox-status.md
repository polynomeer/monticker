# ADR-104: Spring Boot 4 전환 — Boot 매퍼는 Jackson 3(Jackson 2 기본값), Modulith 2 아웃박스 상태 이행

## Status
Accepted

## Context

Spring Boot 4.1(docs/dependency-upgrade-plan.md B4-5)은 Jackson 3(`tools.jackson`)을 기본으로 쓴다. 이 프로젝트에서 JSON이 프로세스 경계를 넘는 경로는 셋이다.

1. **Modulith 아웃박스**: `@Externalized` 이벤트를 Boot가 구성한 매퍼로 직렬화해 `event_publication.serialized_event`에 저장하고, 같은 매퍼로 Kafka에 내보낸다. 재전송 때는 저장된 문자열을 다시 읽는다.
2. **직접 만든 매퍼**: worker의 `WireJson.mapper`(Kafka 틱·알림 등)와 api·worker 곳곳의 `ObjectMapper()`. Boot 설정과 무관하다.
3. **Go market-gateway**: Go 표준 `encoding/json`.

롤링 배포 중에는 구버전(Boot 3.5)과 신버전이 같은 토픽에서 서로의 메시지를 읽는다. 업그레이드 직전에 쌓인 미완료 아웃박스 행도 신버전이 읽어 재전송해야 한다. B4-3에서 지금 형식을 api·worker·Go 양쪽 테스트로 고정했다(`WireFormatGoldenTest`).

Jackson 3의 기본값은 Boot 3.5의 Jackson 2 매퍼와 다르다. 속성을 알파벳순으로 정렬하고(`SORT_PROPERTIES_ALPHABETICALLY`), primitive 자리의 null을 거부하는(`FAIL_ON_NULL_FOR_PRIMITIVES`) 등이다. Boot 3.5도 이미 날짜를 ISO 문자열로 썼으므로 날짜 형식은 같다.

Boot 매퍼를 어떻게 둘지 고려한 대안:

1. **Jackson 3 + `spring.jackson.use-jackson2-defaults=true`** — 채택. Boot가 Jackson 3의 `MapperBuilder.configureForJackson2()`로 Jackson 2 기본값을 되살린 뒤 Boot의 기존 설정(날짜 ISO 문자열 등)을 얹는다.
2. **`spring-boot-jackson2` 모듈로 Boot 매퍼를 Jackson 2로 유지** — 효과가 없다. Modulith 2.1의 `JacksonEventSerializationConfiguration`(Jackson 3)이 `@AutoConfigureBefore(Jackson2EventSerializationConfiguration)`라, Jackson 3이 클래스패스에 있으면(Boot 4에서는 항상 있다) 아웃박스 직렬화는 Jackson 3 매퍼를 쓴다. Kafka 외부화 컨버터도 같다. 게다가 이 모듈 자체가 deprecated다.
3. **Jackson 3 기본값 그대로** — 롤링 배포 중 형식이 어긋날 위험을 떠안는다. 업그레이드와 형식 변경을 한 배포에 섞게 된다.

조사하다가 **Modulith 1.4 → 2.1 아웃박스 이행에서 데이터가 조용히 버려지는 문제**를 발견했다(B4-2의 "값 채우기 불필요" 판단을 뒤집는다). 2.1의 재전송(`resubmitIncompletePublicationsOlderThan`)은 1분이 지난 미완료 행을 고른 뒤, 행마다 다음 문장으로 "가져간다".

```sql
UPDATE event_publication SET status = 'RESUBMITTED', completion_attempts = completion_attempts + 1, last_resubmission_date = ?
 WHERE id = ? AND status != 'RESUBMITTED'
```

갱신된 행이 없으면 다른 인스턴스가 가져간 것으로 보고 건너뛴다.
- **status가 NULL인 행은 가져가지 못한다.** SQL에서 `NULL != 'RESUBMITTED'`는 참이 아니다. Modulith 1.4가 쓴 행(V101이 추가한 컬럼이 비어 있다)은 업그레이드 뒤 **영원히 재전송되지 않는다.** 업그레이드 직전에 쌓인 행도, 롤링 배포 중 구버전이 계속 쓰는 행도 마찬가지다. worker는 구 공유 테이블에서 이관한 행(`LegacyOutboxDrain`, ADR-094)도 status가 NULL이다.
- **RESUBMITTED에 멈춘 행도 같다.** 재전송이 행을 가져간 뒤 결과를 기록하기 전에 프로세스가 죽으면 그렇게 된다.
- PUBLISHED·PROCESSING에 멈춘 행은 문제없다. 위 조건을 통과해 다음 주기에 재전송된다.

`WorkerOutboxIsolationIntegrationTest`(실제 Postgres + Modulith 2.1 레지스트리)가 이관한 행이 재전송되지 않는 것으로 이를 드러냈다.

## Decision

**Jackson**
- Boot 매퍼는 Jackson 3다. api·worker의 `application.yml`에 `spring.jackson.use-jackson2-defaults: true`를 둔다.
- Boot 매퍼를 주입받는 코드는 `tools.jackson.databind.json.JsonMapper`로 바꾼다. Kafka 외부화 컨버터는 `ByteArrayJacksonJsonMessageConverter`로 바꾼다.
- 직접 만든 매퍼는 이번에 바꾸지 않는다. Jackson 2(`com.fasterxml.jackson`)로 남기고 의존성을 명시한다. Boot 4는 Jackson 2를 끌어오지 않지만 2.x BOM은 계속 관리한다. 이 매퍼들은 B4-6에서 하나씩 옮긴다.
- worker `BootJsonFormatGoldenTest`가 실제 `application.yml`로 구성한 Boot 매퍼의 출력을 고정한다. 같은 문자열을 api `WireFormatGoldenTest`가 읽는 쪽에서 고정한다.

**아웃박스 상태 이행**
- `LegacyPublicationStatusBackfill`을 둔다. 재전송 주기마다, 재전송 직전에 다음 행을 `status = 'FAILED'`, `completion_attempts = COALESCE(completion_attempts, 0)`으로 표시한다.
  - 대상: `status IS NULL AND completion_date IS NULL AND publication_date < now() - 1분`
  - 기준(미완료, 1분 경과)은 지금까지의 재전송 정책과 같다.
  - 한 번만 도는 Flyway 백필로는 부족하다. 롤링 배포 중 구버전이 계속 NULL 행을 쓰기 때문이다.
- `spring.modulith.events.staleness.resubmitted: 1m`를 설정한다. RESUBMITTED에 1분 넘게 멈춘 행을 Staleness Monitor가 FAILED로 바꾸고, 다음 재전송이 집는다.
- 완료된 1.4 행(status NULL, completion_date 있음)은 그대로 둔다. 2.1의 정리 쿼리가 `STATUS = 'COMPLETED' OR STATUS IS NULL`을 함께 지운다.

**api에서 더한 것 (B4-5b)**
- **JPA 레지스트리의 `completionAttempts`는 primitive int다.** `completion_attempts`가 NULL인 행을 하나라도 읽으면 Hibernate가 실패해 재전송 조회 전체가 깨진다(JDBC 레지스트리인 worker는 NULL을 읽는다). V102가 기존 행을 0으로 채우고 `DEFAULT 0`을 둔다. 구버전 pod가 이 컬럼 없이 넣는 행도 0이 된다. api의 `LegacyPublicationStatusBackfill`도 매 주기 NULL을 0으로 채운다.
- **Boot 매퍼 규칙의 예외** — 다음 두 곳은 Boot 매퍼 대신 Jackson 2로 직접 만든 매퍼를 쓴다(B4-6에서 다룬다):
  - **Redis 캐시(`CacheConfig`)**: Jackson 3은 `DefaultTyping.EVERYTHING`을 없앴다. final인 Kotlin data class는 루트에 타입 정보가 실리지 않아 캐시를 읽을 때 `ClassCastException`이 난다. Boot 3.5 매퍼와 같은 설정의 Jackson 2 매퍼 + EVERYTHING을 쓰고, 키 접두사를 `v2:`로 바꿔 롤링 배포 중 구·신 버전 값을 섞지 않는다(TTL ≤ 1시간).
  - **트리 모델로 외부 응답을 읽는 코드(`YahooCandleReader`)**: Jackson 3의 `decimalValue()`·`longValue()`는 null 노드에서 예외를 던진다(Jackson 2는 0). 빠진 봉이 null로 오는 Yahoo 응답 전체를 버리게 된다.
- **Jackson 3.1의 `JsonNode.map(Function)` 멤버가 Kotlin `Iterable.map` 확장을 가린다.** `node.map { }`가 원소마다가 아니라 노드 자체에 적용된다. 원소를 돌 때는 `values()`를 쓴다. 이번에는 타입 오류로 드러났지만(`KisOrderBookProvider`), 결과 타입이 맞으면 조용히 바뀐다. B4-6에서 트리 모델 코드를 옮길 때 가장 먼저 볼 것이다.

## Reasons

- **형식 변경과 업그레이드를 분리한다.** 롤링 배포 중 구·신 버전이 같은 형식을 쓰므로 순서와 무관하게 배포할 수 있다. worker를 먼저, api를 나중에 올려도 된다. 형식을 Jackson 3 기본값으로 바꾸고 싶다면 B4-3 테스트를 고쳐 가며 따로 결정할 수 있다.
- 대안 2(Jackson 2 유지)는 가장 중요한 경로인 아웃박스를 지키지 못한다. 대안 3은 이득이 없다. 지금 형식에서 고칠 것이 없다.
- 상태 이행은 Modulith의 공개 상태 모델(FAILED → 재전송)에 기대므로 내부 구현을 덮어쓰지 않는다. 1.4 시절의 "미완료 1분 경과 = 재전송" 의미론(at-least-once)이 그대로다.

## Consequences

- 매퍼가 두 벌(Jackson 3 Boot 매퍼, Jackson 2 직접 만든 매퍼)인 기간이 생긴다. 의존성도 두 벌이다(`tools.jackson.module:jackson-module-kotlin`, `com.fasterxml.jackson.module:jackson-module-kotlin` + `jsr310`). B4-6이 끝나야 하나가 된다.
- `use-jackson2-defaults`는 Boot가 언젠가 없앨 수 있는 호환 설정이다. 없애기 전에 형식 결정을 다시 해야 한다.
- Jackson 3의 알파벳 정렬은 생성자 속성을 앞에 둔다(`SORT_CREATOR_PROPERTIES_FIRST`). 그래서 Kotlin data class는 설정을 꺼도 출력이 같다. 이 설정이 실제로 지키는 건 생성자 밖 속성의 순서와 읽기 기본값이다. 테스트로 "꺼서 깨지는지"를 보일 수 없다.
- Spring Data Redis 4의 캐시 writer를 `immediateWrites()`로 둔다. 기본값(Lettuce에서 비동기 쓰기)이면 put 직후 읽기가 비고, 쓰기 실패가 `CacheErrorHandler`를 거치지 않는다.
- 재전송 주기마다 UPDATE 한 문장이 늘어난다(api는 두 문장). 인덱스는 `completion_date`에 있고, 대상이 없으면 0건이다.
- 구버전이 막 처리하려는 행을 FAILED로 표시해 중복 발행할 수 있다. 1분 경과 조건으로 줄이지만 없애지는 못한다. 이건 1.4 시절 재전송과 같은 at-least-once다. 컨슈머가 멱등이어야 한다는 전제(ADR-042 등)는 그대로다.

## Revisit When

- 구버전(Boot 3.5) api·worker가 모두 내려가고 한 릴리스가 지나면 `LegacyPublicationStatusBackfill`을 지운다. 그때 status NULL·미완료 행이 0건인지 확인한다.
- B4-6(직접 만든 매퍼 이전)이 끝나 Jackson 2 의존성을 없앨 때.
- Boot가 `spring.jackson.use-jackson2-defaults`를 deprecate할 때. Jackson 3 기본값으로 갈지 정한다.
- Modulith가 재전송의 "가져가기" 조건을 바꿀 때(예: `status IS DISTINCT FROM 'RESUBMITTED'`).
