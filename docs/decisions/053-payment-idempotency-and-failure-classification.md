# ADR-053: 정기결제는 결정적 orderId로 멱등하게 청구하고, PG 실패를 세 가지로 구분한다

## Status
Accepted

## Context

주문 체결 경로에는 장애·부하 검증이 두껍게 쌓여 있다 — 카오스 8종, k6 `order-burst`,
`bench/consistency/verify.py`의 불변식 대조, 10스레드 동시성 통합 테스트
([ADR-051](051-event-triggered-paper-orders.md)). 결제 경로에는 **그 중 하나도 없었다.**

| | 주문 체결 | 결제 |
|---|---|---|
| 카오스 시나리오 | 8종 | 0 |
| 부하 시나리오 | L-05 + 정합성 검증기 | 0 |
| [resilience-plan](../resilience-plan.md) 언급 | §A~F 전역 | **0회** (외부 의존 B1~B8에 PG 없음) |
| 서킷브레이커 | `kis`, `toss` | **없음** (타임아웃 10s만) |
| 멱등성 | 멱등 키 + 유니크 인덱스 + 경합 테스트 | 없음 |
| 클라이언트 단위 테스트 | KIS/Toss 다수 | `TossPgClient` **0개** |

둘 다 외부 HTTP이고, 돈이 움직이고, 재시도가 위험하다는 점에서 같은 부류다. 한쪽만 검증돼 있었다.

### 구체적으로 무엇이 깨져 있었나

**(1) 갱신 orderId에 타임스탬프가 들어 있었다.**

```kotlin
orderId = "renewal_${subscription.id}_${System.currentTimeMillis()}"
```

토스는 같은 orderId로 두 번 청구하지 않는다 — 그게 PG가 제공하는 중복 방어다. 그런데 재시도마다
값이 달라지면 그 방어는 **한 번도 작동하지 않는다.** 배치 재실행, 타임아웃 후 다음 주기 재시도가
전부 새 청구였다.

**(2) 실패가 한 가지뿐이었다.**

`PaymentResult(success = false)` 하나로 "카드 거절", "PG 죽음", "응답 못 받음"이 전부 합쳐져 있었다.
갱신 배치는 그걸 전부 결제 실패로 세어 3회 누적 시 FREE로 강등했다 — **PG가 30분 죽으면 돈 내는
고객이 강등되는 경로.** 반대로 타임아웃(청구됐는지 모름)을 실패로 단정하면 다음 실행이 재청구였다.

**(3) 승인 경로에 서킷브레이커가 없었다.**

[P0-2](../resilience-plan.md)는 "느려지는 외부가 죽는 외부보다 위험하다"는 이유로 모든 브레이커에
`slowCallRateThreshold`를 걸게 했다. 결제만 그 정책 밖에 있었다 — `PAYMENT_READ`(10s) 타임아웃
하나뿐이라, PG가 느려지면 Tomcat 스레드가 하나씩 물려 쌓이는 동안 아무도 못 막았다.

## Decision

### 1. orderId를 (구독, 청구주기)에서 결정적으로 유도하고, DB가 중복을 막는다

```kotlin
fun renewalOrderId(subscription: UserSubscription) =
    "renewal_${subscription.id}_${subscription.expiresAt?.epochSecond ?: 0L}"
```

만료시각은 **갱신이 성공해야만** 바뀐다. 그래서 같은 주기 안에서는 몇 번을 재시도해도 같은 값이고,
갱신이 성공하면 다음 주기의 키는 자동으로 달라진다.

그 값을 `payment_records.pg_order_id`에 저장하고 부분 유니크 인덱스를 건다(V50). 프로세스 안의
사전 조회는 빠른 경로일 뿐이고, 실제 방어선은 DB다 — `orders.idempotency_key`와 같은 구조다.

```sql
CREATE UNIQUE INDEX ux_payment_records_pg_order_id
    ON payment_records (pg_order_id) WHERE pg_order_id IS NOT NULL;
```

### 2. PG 실패를 세 가지로 나눈다

| 종류 | 의미 | 청구됐나 | 갱신의 대응 |
|---|---|---|---|
| `DECLINED` | PG가 정상 응답했고 거절했다 (4xx, 잔액 부족) | **아니오** | 실패로 기록, 연속 실패 카운트 |
| `UNAVAILABLE` | 요청이 PG에 닿지 못했다 (브레이커 OPEN, connect 실패) | **아니오** | PENDING 유지, **카운트하지 않음** |
| `INDETERMINATE` | 요청은 갔는데 응답을 못 받았다 (read 타임아웃, 5xx) | **모름** | orderId로 PG에 되물은 뒤 판정 |

불확정 복구는 `GET /v1/payments/orders/{orderId}`로 한다. paymentKey는 PG가 응답에 실어주므로
응답을 못 받았으면 우리에겐 없다 — **우리가 만든 orderId로 물어야 하고, 그래서 orderId가 결정적이어야 한다.**

조회 실패(`lookupFailed`)와 "결제 없음"(`found=false`)도 구분한다. 이 둘을 섞으면 복구가 정반대로
동작한다 — 조회가 실패했을 뿐인데 "결제 안 됐다"로 읽고 재청구한다.

### 3. `tossPg` 서킷브레이커

```kotlin
.failureRateThreshold(50f)
.slowCallRateThreshold(50f)
.slowCallDurationThreshold(Duration.ofSeconds(5))   // PAYMENT_READ(10s)보다 짧게
.slidingWindowSize(6)
.waitDurationInOpenState(Duration.ofSeconds(60))    // 브로커(30s)보다 길게
.permittedNumberOfCallsInHalfOpenState(2)           // 조회 + 청구
.ignoreExceptions(HttpClientErrorException::class.java)   // 4xx는 장애가 아니다
```

브로커와 다르게 잡은 값에는 각각 이유가 있고, 뒤의 두 줄은 **실험이 고치게 만든 것**이다(§실험 참고).

## 실험 — CH-13 / CH-14

`bench/chaos/toss-pg-stub.py`를 PG 자리에 세운다(CH-06의 `kis-stub.py`와 같은 방식). 스텁은
`delay / down / error / timeout` 네 모드를 갖고, **청구 횟수와 orderId를 집계한다** — 이중청구는
"같은 orderId로 두 번 왔는가"로만 증명되고, 그건 PG 쪽에서 세야 보인다.

```
환경   로컬 docker-compose (postgres/redis/mongodb/elasticsearch) + api jar (local 프로파일)
       macOS Apple Silicon, PG_MOCK_ENABLED=false, TOSS_PG_BASE_URL=스텁
날짜   2026-09-30
대조군 실험마다 PG가 정상일 때 갱신 대상 구독을 새로 하나 만들고(seed_renewal_candidate),
       이전 실험 잔여분은 만료를 미뤄 창 밖으로 밀어낸다
```

### 실험이 찾아낸 것 — **정기결제 갱신은 한 번도 동작한 적이 없었다**

첫 실행에서 배치가 매번 FAILED로 끝났다.

```
NoSuchMethodException: findExpiringBefore(java.time.Instant, PageRequest)
  → skip limit 초과 → Step FAILED → 갱신 0건
```

`RepositoryItemReader`는 설정한 arguments 뒤에 `PageRequest`를 덧붙여 **리플렉션으로** 호출하고,
반환을 `Slice`로 캐스팅한다. 리포지토리 메서드는 `(Instant) -> List` 였다. 메서드 이름이 문자열이라
컴파일러가 이 계약을 검사하지 않는다.

여기에 더해 **수동 실행 엔드포인트가 FAILED에도 200을 돌려주고 있었다.** 응답만 봐서는 보이지 않았고,
아무도 로그를 열어보지 않았다. 이건 [CH-05](../resilience-plan.md)(아웃박스가 한 번도 발행한 적 없음),
[ADR-043](043-ledger-pagination-and-reconciliation.md) E2(원장 INSERT가 전부 실패 중)와 같은 계열의 발견이다 —
**검증이 없는 경로는 조용히 죽어 있다.**

리더가 싱글턴이라 `Instant.now()`가 기동 시각으로 굳는 문제도 같이 고쳤다(`@StepScope`).

### 실험이 고치게 만든 설정 두 가지

**`permittedNumberOfCallsInHalfOpenState`를 1 → 2로.** 갱신 한 건이 복구되려면 호출이 두 번 필요하다 —
먼저 "이미 청구됐나" 조회, 아니면 그제서야 청구. 1이면 조회가 유일한 프로브를 소진하고 청구는
`CallNotPermitted`로 막혀, 갱신이 다음 배치 주기까지 밀린다. **갱신 배치는 월 1회라 그 "다음 주기"가 한 달이다.**

**4xx를 브레이커 집계에서 제외.** 복구 경로의 첫 동작이 orderId 조회이고, **정상 답이 404**다.
그 404 하나가 HALF_OPEN을 즉시 OPEN으로 되돌려 뒤따르던 갱신 6건이 전부 `CallNotPermitted`로
밀리는 것을 관측했다 — 복구가 자기 브레이커를 스스로 오염시켰다. 브레이커는 가용성을 보는 장치지
비즈니스 결과를 보는 장치가 아니다. 같은 수정이 "카드가 무더기로 거절되는 날 브레이커가 열려
멀쩡한 결제까지 막는" 경로도 함께 닫는다.

### 결과

**CH-13 — PG 전면 정지: PASS**

| 단계 | 빌링키 등록 | 무관한 API | 브레이커 | 갱신 배치 |
|---|---|---|---|---|
| 정상 | 200, 45ms | 스크리너 200 100ms | closed | — |
| **PG 정지** | **400, 6~14ms** (매달리지 않음) | 스크리너 200 **55ms**, 검색 200 131ms, readiness 200 | **open** (8회 중) | 3회 실행 → **PENDING 1건, PG 청구 0건, FAILED 0건, 강등 0건** |
| 복구 후 | 200, 62ms | — | half_open → closed | 1회 실행 → PENDING **→ SUCCESS**, orderId 동일, **총 청구 1회** |

동시 10건 등록이 **632ms**에 끝났다 — 브레이커가 없었다면 10번의 연결 시도를 각각 기다린다.

**CH-14 — PG 지연·불확정: PASS**

| 관측 | 값 |
|---|---|
| 6초 응답 3회 뒤 브레이커 | **OPEN** — `failure_rate = 0%`, `slow_call_rate = 50%` |
| OPEN 후 호출 | 6~19ms (거절), 그 전엔 6026~6138ms |
| 동시 10건 | **632ms** (브레이커 없으면 10 × 6s) |
| read 타임아웃 | 10072ms에 반환 — `PAYMENT_READ`(10s)가 실제로 발동 |
| **불확정 중 배치 3회** | 기록 **PENDING 유지**(FAILED로 굳히지 않음), PG 청구 **1회**(최초 타임아웃 건) |
| **복구 후 배치 1회** | 조회 1회 → 이미 청구됨 확인 → PENDING **→ SUCCESS**, orderId 동일 |
| **총 청구** | **1회** — 배치 4회 실행, 우리는 원래 응답을 끝내 받지 못했는데도 |

마지막 줄이 이 ADR의 전부다. 고치기 전이라면 배치 #2가 새 `renewal_13_<새millis>`로 **두 번째 청구**를 했다.

`failure_rate = 0%`인데 브레이커가 열린 것도 그 자체로 의미가 있다 — 호출이 전부 "성공"이라
실패율만 보는 브레이커였다면 영원히 닫혀 있었을 상황이다.

## Reasons

**왜 프로세스 내 검사가 아니라 DB 유니크 인덱스인가.** 갱신 배치가 두 인스턴스에서 동시에 돌거나
타임아웃 재시도가 겹치면 두 스레드가 모두 사전 조회를 통과한다. MockK 단위 테스트는 순차 실행이라
이 레이스를 원리적으로 재현하지 못한다. `PaymentIdempotencyIntegrationTest`가 실제 10스레드로 친다.

**왜 만료시각을 주기 식별자로 쓰는가.** 월/연도 문자열은 "이번 달에 두 번 갱신해야 하는" 경우(무료
체험 종료 직후 등)를 구분하지 못한다. 만료시각은 갱신이 성공해야 바뀌므로 정확히 "지금 처리 중인
청구 주기"를 가리킨다.

**왜 UNAVAILABLE을 카운트하지 않는가.** 3회 강등 규칙은 "이 고객의 카드가 계속 안 된다"를 잡으려고
만든 것이다. PG 장애는 고객에 대한 정보가 아니다. 이걸 섞으면 장애 한 번이 강등 파도가 된다.

**왜 INDETERMINATE에서 되묻는가.** 셋 중 유일하게 "모른다"가 답인 경우다. 실패로 단정하면 이중청구,
성공으로 단정하면 무료 이용이다. PG만이 답을 갖고 있고, 물어볼 열쇠가 orderId다.

## Consequences

**PENDING 결제 기록이 쌓인다.** 불확정·장애로 판단을 미룬 흔적이다. 정상 상태에서는 다음 배치가
정리하지만, PG가 오래 죽어 있으면 그동안 남는다. `verify.py`가 건수를 출력하고, 계속 늘면 사람이
봐야 한다는 신호다. **PENDING이 영원히 남는 경우(예: 구독이 그 사이 해지됨)를 청소하는 배치는 아직 없다.**

**복구가 한 주기 늦어질 수 있다.** half-open 프로브가 2회라 조회+청구가 한 번에 통과하지만, 프로브가
실패하면 브레이커가 다시 60초 닫힌다. 갱신 배치가 월 1회이므로 실무에서는 **배치를 실패 건에 대해
더 자주 재시도하는 스케줄이 필요하다.** 지금은 수동 트리거뿐이다.

**일회성 결제(confirm 플로우)는 아직 orderId를 기록하지 않는다.** 유니크 인덱스가 부분 인덱스인
이유이고, 그래서 confirm 중복 호출에 대한 DB 차원의 방어는 없다. 프론트가 같은 paymentKey로 두 번
confirm하면 토스가 2회차를 4xx로 거절하지만, 그때 우리는 "결제 실패"로 읽고 400을 돌려준다 —
실제로는 성공한 결제인데도. **이 경로는 이 ADR의 범위 밖이고, 남은 갭이다.**

**스텁은 토스가 아니다.** CH-13/14는 우리 쪽 동작(브레이커, 분류, 멱등성)을 검증하지, 토스가 실제로
같은 응답을 주는지는 검증하지 못한다. 특히 `GET /v1/payments/orders/{orderId}`의 404 동작은 문서를
근거로 가정한 것이고 실계정으로 확인한 적이 없다.

**4xx를 브레이커에서 제외한 것의 이면.** PG가 400만 계속 뱉는 오작동 상태(스펙 변경, 키 만료 등)는
이제 브레이커가 잡지 못한다. 그건 브레이커가 아니라 알림으로 잡아야 한다 —
`payment_records.status='FAILED'` 급증에 대한 알림 규칙은 아직 없다.

## Revisit When

- 일회성 결제(confirm)에도 서버 생성 orderId를 도입할 때 — 부분 유니크 인덱스를 전체로 바꿀 수 있다
- 실제 토스 테스트 계정으로 CH-13/14를 다시 돌릴 때 — 스텁 가정(404 동작, 상태 전이)이 맞는지 확인
- 갱신 실패 건 재시도 스케줄을 도입할 때 — `waitDurationInOpenState`와 배치 주기의 관계를 다시 계산
- PG를 추가하거나 교체할 때 — `PgClient` 인터페이스의 `findPaymentByOrderId` 계약을 새 PG가 만족하는지
- PENDING 적체 청소 배치를 만들 때
