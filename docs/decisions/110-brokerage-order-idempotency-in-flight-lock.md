# ADR-110: 실거래 주문 멱등성 — 처리 중 잠금·본문 바인딩·결과 불명 고정

## Status
Accepted — [ADR-007](007-idempotency-key-filter.md)의 "2xx만 캐시, 실패는 재시도 허용" 결정을 `POST /api/brokerage/orders`에 한해 대체한다.
나머지 경로(`/api/paper/buy|sell`, `/api/matching/orders`, `/api/subscription/payment/confirm`)는 ADR-007 동작 그대로다.

## Context

모바일 실거래 주문 시트 보안 점검에서 `IdempotencyFilter`의 결함 세 가지가 나왔다. 모두 같은 `X-Idempotency-Key`로 실주문이
두 번 나갈 수 있는 경로다.

1. **2xx만 캐시한다.** 증권사 호출 뒤 5xx(결과 기록 실패, 응답 직렬화 실패)나 `OrderOutcomeUnknownException`(409, [ADR-056](056-brokerage-order-unknown-outcome.md))
   으로 끝나면 키에 아무것도 남지 않는다. 클라이언트가 타임아웃·오류 뒤 같은 키로 재시도하면 주문이 다시 실행된다.
   ADR-007은 "실패는 재시도할 수 있게 캐시하지 않는다"고 했지만, 실거래에서는 *실패처럼 보이는 결과 불명*이 있다.
2. **조회 후 저장 사이에 잠금이 없다.** 같은 키의 두 요청이 거의 동시에 오면 둘 다 캐시 미스를 보고 둘 다 실행한다.
   더블 탭, 클라이언트 재시도와 원 요청의 겹침이 이 경우다.
3. **키가 요청 본문과 묶여 있지 않다.** 같은 키로 다른 주문(종목·수량이 다른)을 보내면 앞선 주문의 응답을 성공으로 돌려준다.
   클라이언트는 새 주문이 체결됐다고 오해한다.

검토한 대안:

- (a) **DB 기반 멱등성 저장소** — `brokerage_orders`에 `(user_id, idempotency_key)` 유니크 제약을 두고 의도 커밋(tx1)에서 잡는다.
  가장 강하지만 필터 레이어의 공통 메커니즘을 버리고 주문 서비스 계약을 바꾼다. 결과 불명 응답 재생도 따로 만들어야 한다.
- (b) **필터에서 Redis SETNX 처리 중 표시 + 본문 해시 + 결과 불명 고정** — 기존 필터와 fail-closed 정책(resilience-plan §A1)을 그대로 쓴다.
- (c) 클라이언트만 고친다 — 서버가 막지 않으면 웹·외부 클라이언트·버그 있는 재시도는 막을 수 없다.

## Decision

(b)를 택한다. `POST /api/brokerage/orders`에서 `X-Idempotency-Key`가 있으면:

```
본문 읽기 → SHA-256(키 정렬한 JSON) = bodyHash
SET idempotency:{userId}:{key} {"state":"IN_FLIGHT","bodyHash"} NX EX 24h
  ├─ Redis 실패 → 503 + Retry-After (fail-closed, 기존과 같음)
  ├─ 이미 있음 → GET
  │    ├─ bodyHash 다름            → 422 IDEMPOTENCY_KEY_REUSED (실행 안 함)
  │    ├─ IN_FLIGHT (또는 막 만료) → 409 IDEMPOTENCY_IN_FLIGHT "같은 주문을 처리 중입니다"
  │    └─ 완료 항목               → 저장된 status·body 그대로 재생
  └─ 획득 → 실행
       ├─ 2xx                                   → {status, body, bodyHash} 저장
       ├─ 5xx, 또는 IndeterminateOutcome 예외    → "주문 결과 확인이 필요합니다" 본문(code ORDER_OUTCOME_UNKNOWN,
       │   (응답 전 예외 포함)                      orderId 있으면 포함)으로 바꿔 응답하고 같은 것을 저장
       └─ 그 밖의 4xx (증권사 호출 전 확정 실패) → 키 삭제 — 고친 뒤 같은 키로 다시 보낼 수 있다
```

- **결과 불명 신호**: 필터는 응답만 본다. 그래서 공용 커널에 `IndeterminateOutcome` 인터페이스를 두고 `OrderOutcomeUnknownException`이
  구현한다. `GlobalExceptionHandler`가 이 타입이면 요청 속성에 남기고, 필터가 그걸 읽는다. 상태 코드는 409 그대로다.
- **5xx는 모두 결과 불명으로 본다.** 응답만으로는 증권사 호출 전인지 후인지 알 수 없다. 실제 돈이 걸린 경로라 "나갔을 수 있다" 쪽으로 틀린다.
- **처리 중 표시 TTL은 결과와 같은 24시간이다.** 실행 중 서버가 죽으면 표시가 남는다. 짧게 두면 만료 뒤 같은 키 재시도가 주문을 다시
  실행한다. 남은 키는 24시간 동안 409를 받는다. 클라이언트는 새 주문에 새 키를 쓰므로 새 주문은 막히지 않는다.
- **결과 저장 실패**: 처리 중 표시가 남아 재시도는 409가 된다. ADR-007에서는 이 경우 재실행됐다.
- **해제 실패**: 키가 24시간 처리 중으로 남는다. 거절 쪽으로 틀리는 것이라 그대로 둔다(WARN 로그).
- **이전 형식 항목**(`bodyHash` 없음)은 비교 없이 재생한다. 배포 후 24시간이 지나면 남지 않는다.
- 해시는 JSON을 키 순으로 정렬해 다시 직렬화한 값에 낸다. 필드 순서만 다른 같은 주문은 같은 주문이다. JSON이 아니면 원문 바이트에 낸다.
- 새 Redis 연산 `idempotency_lock`·`idempotency_release`도 `redis_command_failed_total{policy="closed"}`에 미리 등록한다.

## Reasons

- 필터 레이어라 주문 서비스·DB 스키마를 바꾸지 않는다. Redis 장애 시 fail-closed 정책도 그대로다.
- SETNX 하나로 동시 실행을 막는다. 분산 락 라이브러리나 대기 없이, 두 번째 요청은 즉시 409를 받는다.
- 결과 불명을 "응답 그대로 고정"하면 첫 응답과 재시도 응답이 같다. 클라이언트는 한 가지 경우만 다루면 된다.
  모바일 `classifyLiveResult`는 5xx와 예상 밖 4xx(409 포함)를 이미 "결과 확인 필요"로 다룬다.
- 확정 실패만 키를 푼다. ADR-007이 지키려던 "실패하면 재시도할 수 있다"를 증권사 호출이 없었음이 확실한 경우로 좁혔다.

## Consequences

- 5xx 중에는 증권사 호출 전에 난 것도 있다(예: 의도 커밋 중 DB 장애). 그 키도 24시간 고정된다. 사용자는 주문 내역을 확인한 뒤
  새 주문(새 키)을 넣어야 한다. 중복 주문보다 낫다고 본다.
- 이 경로는 요청 본문 전체를 메모리로 읽는다. 주문 본문은 작은 JSON이라 문제없다.
- 422가 두 뜻을 갖는다 — 리스크 한도 초과(`RiskLimitException`)와 키 재사용. 본문 `code`(`IDEMPOTENCY_KEY_REUSED`)로 구분한다.
  모바일은 422를 "리스크 한도로 주문하지 않음"으로 보여준다. 새 요청이 실행되지 않은 것은 맞지만, 결과 불명 뒤 같은 키로 다른 주문을
  보냈다면 문구가 앞선 주문의 불확실성을 가린다. 모바일이 결과 불명 뒤 입력을 바꾸면 새 키를 쓰게 하는 것이 맞다.
- 409가 세 뜻을 갖는다 — 비즈니스 규칙 위반, 결과 불명(`ORDER_OUTCOME_UNKNOWN`), 처리 중(`IDEMPOTENCY_IN_FLIGHT`). `code`로 구분한다.
- 키가 없는 요청은 여전히 보호받지 않는다(ADR-007과 같다). 웹 주문 화면도 키를 보내도록 함께 바꿨다.
- 다른 멱등성 경로(모의투자·결제 확정)는 여전히 2xx만 캐시하고 처리 중 잠금이 없다. 결제 확정은 Toss 쪽 멱등성이 있다.

## Revisit When

- 모의투자·결제 확정 경로에도 동시 중복이 관측될 때 — `strictPaths`에 넣으면 된다.
- 조건부 주문·리밸런싱 실행처럼 사용자 요청이 아닌 경로가 같은 보호를 필요로 할 때 — 그때는 (a) DB 유니크 제약이 맞다.
- Redis가 멱등성 저장소로 부족해질 때(복제 지연으로 SETNX가 두 노드에서 성공하는 등) — (a)로 옮긴다.
