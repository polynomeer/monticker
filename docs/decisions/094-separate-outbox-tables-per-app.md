# ADR-094: api·worker의 Outbox 발행 기록을 테이블로 분리한다 (worker는 JDBC 레지스트리 + 별도 스키마)

## Status
Accepted

[ADR-042](042-outbox-based-es-indexing.md) §2의 "worker도 `event_publication`을 api와 공유한다"를 대체한다. ADR-042의 나머지
(ES 인덱싱을 Outbox 단일 파이프라인으로)와 [ADR-008](008-outbox-pattern-spring-modulith.md)·[ADR-065](065-user-notifications-from-api.md)의
Outbox 보장은 그대로다.

## Context

2026-10-08 로컬 스택에서 확인했다. api와 worker는 빌드·배포가 따로인 두 Spring Boot 앱이고 Postgres 하나를 공유한다. 둘 다
Modulith JPA 발행 레지스트리를 썼고, JPA 레지스트리의 엔티티는 테이블 이름이 `EVENT_PUBLICATION`으로 고정이라 **같은
`public.event_publication`** 을 썼다.

각 앱의 `OutboxResubmissionConfig`는 5분마다 `resubmitIncompletePublicationsOlderThan(1m)`을 부른다. 이 호출은 1분 지난
미완료 행을 **전부** 엔티티로 읽는다. `event_type` 열은 `Class<?>`로 매핑돼 있다. 그래서 상대 앱에만 있는 클래스의 행을
만나면 Hibernate가 `Unable to locate named class …`를 던지고, 재전송 **전체**가 실패한다. 예를 들어 api는 worker의
`SearchIndexEvent`를, worker는 api의 `UserNotificationCommand`를 해석하지 못한다. 상대 행이 남아 있는 한 이 실패는 5분마다
영원히 반복된다.

- 실측: Kafka가 내려가 있는 동안 api가 기록한 `UserNotificationCommand` 2건이 끝내 재전송되지 않았다. 사용자 알림이 사라졌다.
  [ADR-056](056-brokerage-order-unknown-outcome.md)의 주문 결과 불명 알림도 같은 경로다.
- 기존 worker 주석의 "자기 리스너 id만 재전송한다"는 틀렸다. 리스너를 고르기 전에 클래스 해석에서 먼저 죽는다.
- 완료 행 정리가 없었다. Modulith 기본 완료 모드(UPDATE)는 행을 지우지 않는다. 로컬에 api ~13k행, worker ~50k행이 쌓였다.

고려한 대안:

| | 방식 | 판단 |
|---|---|---|
| (a) | **worker를 JDBC 레지스트리로 바꾸고 `spring.modulith.events.jdbc.schema=worker_outbox`** | **채택.** Modulith 1.4가 문서화한 설정 하나로 테이블이 갈린다 |
| (b) | JPA 레지스트리 유지 + `orm.xml`로 worker의 `DefaultJpaEventPublication` 테이블 덮어쓰기 | JPA 표준이지만 Modulith **내부 패키지의 엔티티 클래스명**(`…jpa.updating.DefaultJpaEventPublication`)에 묶인다. 2.x에서 이름이 바뀌면 덮어쓰기가 조용히 빠질 수 있다 |
| (c) | 재전송을 직접 구현(`event_type LIKE 'com.monticker.api.%'`만 읽어 리스너 호출) | Modulith 내부(`PublicationTargetIdentifier` 해석, 완료 표시, 역직렬화)를 다시 짜야 한다. 버전업마다 어긋난다 |
| (d) | 두 앱 모두 JPA 유지, api 쪽만 클래스 해석 실패 행을 건너뛰게 패치 | Modulith에 그런 확장점이 없다. 쿼리를 바꾸려면 리포지토리를 갈아끼워야 해 결국 (c)다 |
| (e) | 완료 모드 DELETE로 행을 바로 지워 충돌 확률만 낮추기 | 미완료 행이 문제라 해결이 아니다 |

## Decision

1. **api**: 그대로 Modulith JPA 레지스트리, `public.event_publication`([V18](../../backend/api/src/main/resources/db/migration/V18__create_spring_modulith_event_publication.sql)).
   이제 이 테이블은 api 전용이다.
2. **worker**: `spring-modulith-starter-jpa` → `spring-modulith-starter-jdbc`. `application.yml`에
   `spring.modulith.events.jdbc.schema: worker_outbox`를 둔다. Modulith 스키마 초기화는 끈다. 테이블은 Flyway가 소유한다.
   - Outbox 보장은 유지된다. JDBC 레지스트리는 `JdbcTemplate`으로 쓴다. worker 서비스의 트랜잭션은 `JpaTransactionManager`이고,
     이 매니저가 같은 커넥션을 스레드에 묶는다. 그래서 발행 기록은 업무 변경과 같은 트랜잭션에서 커밋·롤백된다(통합 테스트로 확인).
3. **V91**(api Flyway): `worker_outbox` 스키마와 `worker_outbox.event_publication`을 만든다. 구조와 인덱스는 Modulith 1.4.13
   JDBC `schema-postgresql.sql`과 같다(`serialized_event` 해시, `completion_date`). 이어서 아래 행을 `DELETE … RETURNING` →
   `INSERT` 한 문장으로 옮긴다.
   - 대상: `public.event_publication`의 **미완료 + `event_type LIKE 'com.monticker.worker.%'` + 1분 이상 경과** 행.
   - 판별 기준이 패키지인 이유: 재전송 실패의 원인이 "이 클래스를 해석할 수 있는 앱"이기 때문이다.
   - `public.event_publication`의 구조는 바꾸지 않는다.
4. **전환 이관**(worker `outbox.LegacyOutboxDrain`): 새 worker가 재전송 직전마다 V91과 같은 규칙으로 public에서 옮긴다.
   V91 뒤에도 떠 있는 구버전 worker가 public에 쓴 행이 대상이다. 이관이 실패해도 자기 테이블 재전송은 계속한다.
5. **기동 가드**(worker `outbox.WorkerOutboxSchemaGuard`): `worker_outbox.event_publication`이 없으면 worker는 기동하지 않는다.
   같은 클래스가 `outbox_pending`·`outbox_oldest_age_seconds` 게이지를 worker 테이블 기준으로 내보낸다. 기존 `OutboxBacklog`
   경보는 job 필터가 없어 worker 적체에도 울린다.
6. **완료 행 정리**(양쪽 `outbox.OutboxCompletedCleanup`): 1시간마다 `CompletedEventPublications.deletePublicationsOlderThan(7일)`.
   - api는 JPQL 일괄 삭제라 클래스를 해석하지 않는다. 그래서 public에 남은 구버전 worker의 완료 행(~50k)도 함께 지운다.
     V91은 완료 행을 옮기지 않는다.

### 배포 순서: **api → worker**

| 시점 | 구버전 api | 새 api | 구버전 worker | 새 worker |
|---|---|---|---|---|
| api 롤아웃 중(V91 적용) | public 사용, 구조 불변 → 영향 없음 | public 사용 | public 사용(JPA `ddl-auto=validate` 통과), 새 스키마는 무시 | — |
| worker 롤아웃 중 | — | public 사용 | public에 계속 씀 | worker_outbox 사용, 구버전 행을 주기마다 이관 |
| 완료 후 | — | public(api 전용) | — | worker_outbox(worker 전용) |

worker를 먼저 배포하면 기동 가드가 새 pod를 멈춘다. 롤링 업데이트에서는 구버전 pod가 계속 일한다. api가 V91을 적용하면
새 pod가 재시작 끝에 올라온다. docker-compose `msa` 프로필은 원래도 api 마이그레이션 전에는 worker가 뜨지 못했다(`ddl-auto=validate`).
그 전제는 그대로다.

### 보존 기간 7일의 근거

- 완료 행을 읽는 코드는 없다. 재전송, 게이지, [원장 불일치 런북](../runbooks/ledger-mismatch.md)은 모두 미완료 행만 본다.
- 그래도 바로 지우지(DELETE 완료 모드) 않는다. 중복 발행이나 유실을 조사할 때 "언제 기록돼 언제 외부화됐나"를 보여 주는
  유일한 흔적이기 때문이다.
- 7일이면 주말을 낀 사고를 다음 주 초에 조사해도 흔적이 남는다.
- 1시간 주기면 평시 한 번에 지우는 양이 한 시간치다. api의 `statement_timeout` 30s에 한참 못 미친다.

## Reasons

- **분리가 구조적이다.** 각 레지스트리는 자기 테이블만 읽는다. 상대 앱이 어떤 클래스를 기록하든 재전송이 깨질 수 없다.
  "조심해서 쓰기"에 기대는 방식이 아니다.
- **Modulith의 공개 설정만 쓴다.** `spring.modulith.events.jdbc.schema`는 1.4 레퍼런스에 있는 설정이다. 내부 엔티티명(b)이나
  재전송 로직(c)에 묶이지 않는다.
- 이관은 한 문장이라 행이 두 곳에 남거나 사라지지 않는다. 1분 유예는 재전송 기준과 같다. 그래서 at-least-once 의미론에
  새 중복 경로를 더하지 않는다.
- api 쪽은 테이블, 엔티티, 쿼리가 하나도 바뀌지 않는다. 주문과 알림 경로의 위험이 가장 작다.

## Consequences

- 두 앱의 레지스트리 구현이 다르다(api JPA, worker JDBC). Modulith를 올릴 때 두 구현의 스키마 변경을 각각 확인해야 한다.
  [dependency-upgrade-plan](../dependency-upgrade-plan.md)의 Modulith 2.x 항목에 해당한다. 2.x에는 상태 열이 추가된다.
- worker가 `worker_outbox` 스키마를 쓴다. 지금은 두 앱이 같은 DB 계정이라 권한 문제가 없다. 계정을 나누면 worker 계정에
  `worker_outbox` USAGE/DML 권한을 줘야 한다.
- 전환 구간이 남는다. V91 적용부터 worker 롤아웃이 끝날 때까지 구버전 worker가 public에 쓴 행이 1분을 넘기고 미완료로
  남을 수 있다. 그런 행이 있으면 api 재전송은 그동안 예전처럼 실패한다. 새 worker의 이관이 돌면 풀린다.
- 이관이 행을 옮기는 사이 구버전 worker가 같은 행을 재전송하면 그 이벤트는 두 번 나갈 수 있다. at-least-once 범위다.
  `search.index`는 문서 id 기준이라 멱등이다. `market.event-detected`는 소비자의 멱등 키(`WR:{ruleId}:{eventId}`, ADR-051)가
  중복 주문을 막는다.
- Grafana의 Outbox 패널은 `job="monticker-api"`로 필터한다. worker 적체는 경보로만 보인다.
  (2026-10 후속: 패널이 api·worker를 따로 그린다. `OutboxBacklog`는 api로 한정했고 worker는 `WorkerOutboxBacklog`가 받는다.
  런북은 [outbox-backlog.md](../runbooks/outbox-backlog.md).)
- 테스트: api `OutboxIsolationIntegrationTest`, `V91SeparateWorkerOutboxMigrationIntegrationTest`, worker
  `WorkerOutboxIsolationIntegrationTest`. Testcontainers로 실제 레지스트리, 운영 `application.yml`, V91을 쓴다.
  - 1분 지난 미완료 알림이 재전송된다.
  - 상대 앱의 미완료 행이 있어도 재전송이 성공한다.
  - 공유 테이블이었다면 실패한다(회귀 증명).
  - V91이 옮기는 행과 남기는 행이 맞다.
  - 롤백하면 발행 기록도 롤백된다.
  - 기동 가드가 동작한다.

## Revisit When

- 모든 환경에서 구버전 worker가 내려간 지 한 릴리스가 지나면 `LegacyOutboxDrain`을 지운다. `public.event_publication`에
  `com.monticker.worker.%` 미완료 행이 0건인지 먼저 확인한다.
- Modulith 2.x로 올릴 때: JDBC 레지스트리도 api와 같은 JPA로 맞출지, 반대로 api도 JDBC + 스키마로 옮길지 다시 본다.
- 세 번째 앱이 Modulith 발행을 시작할 때: 같은 원칙으로 앱마다 자기 스키마를 쓴다.
- 조사에 7일보다 긴 흔적이 필요해지면 보존 기간을 늘리지 말고 ARCHIVE 완료 모드를 검토한다.
