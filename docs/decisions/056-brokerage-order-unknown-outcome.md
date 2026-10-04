# ADR-056: 실거래 주문의 "결과 불명"을 일급 상태로 — 의도 선기록, 실패 3분류, 브로커 대조

## Status
Accepted

[ADR-053](053-payment-idempotency-and-failure-classification.md)(결제 실패 3분류)의 원리를 **실거래 주문**으로 옮긴다.
[ADR-025](025-real-brokerage-order-safety-gate.md)·[ADR-032](032-conditional-orders.md)·[ADR-034](034-rebalancing-execution.md)의
주문 제출 경로를 바꾸며, 각 결정은 그대로 유효하다.

## Context

[2026-10 설계 리뷰](../design-review-2026-10.md) 사고실험 3·4·5에서 확인한 것:

1. **타임아웃이 "거부"로 기록된다.** `KisBrokerageClient`·`TossBrokerageClient`의 `submitOrder`가 `RestClientException`을 전부
   `REJECTED`로 바꾼다. 읽기 타임아웃(5s)은 "요청은 갔는데 응답을 못 받았다"이므로 증권사에서 체결됐을 수 있다. 사용자는
   "거부"를 보고 다시 누른다 → **이중 주문**. 조건부 주문은 `FAILED`가 되고, 리밸런싱 leg는 실패로 남는다 — 실제 포지션은
   바뀌었는데.
2. **주문 행이 브로커 호출 *뒤에* 저장된다.** `BrokerageService.submitOrder` 전체가 하나의 `@Transactional`이고, 브로커
   호출이 그 트랜잭션 **안에서** 일어난다. 브로커가 접수한 뒤 DB 저장이 실패하거나 프로세스가 죽으면 **흔적이 0**이다.
3. **조건부 주문 `TRIGGERED` 고착.** 원자적 클레임(커밋) 후 브로커 호출 전에 죽으면 그 행은 영원히 `TRIGGERED`다. 나갔는지
   안 나갔는지 알 방법이 없다.
4. **조회 실패를 "제출됨"으로 읽는다.** `getOrderStatus`가 조회 실패 시 `SUBMITTED`를 돌려준다. ADR-053이 경고한 바로 그
   혼동이다("조회 실패"와 "주문 없음"을 섞으면 복구가 정반대로 동작한다).
5. **시간당 주문 수 리스크 룰이 실패한 제출을 세지 않는다.** 행이 제출 성공 뒤에만 생기기 때문이다.

결과 불명을 해소하려면 **우리가 만든 식별자로 증권사에 되물을 수 있어야 한다**(ADR-053의 핵심). 두 증권사를 확인했다:

| | 클라이언트 주문 ID | 그 ID로 조회 | 당일 주문 목록 |
|---|---|---|---|
| KIS | 없음 | 불가 | `inquire-daily-ccld` (종목·매매구분 필터, 주문시각 `ord_tmd` 초 단위) |
| Toss (OpenAPI v1.2.19) | `clientOrderId` — 멱등 키, **10분 유효** | **불가** — `Order` 스키마에 `clientOrderId` 없음 | `GET /api/v1/orders?symbol&from&to&status=OPEN\|CLOSED`, `orderedAt` |

Toss는 같은 `clientOrderId`로 **재요청**하면 이전 결과를 돌려준다(원주문이 없었다면 그때 새로 접수된다). 이걸로 확정할 수
있지만, 원주문이 도달하지 않았던 경우 **사용자가 확인하지 못한 시점에 주문이 새로 나간다** — 시장가면 사용자가 본 가격과
다르게 체결된다.

고려한 대안:

- **A. 재시도로 해결** — 실패하면 다시 보낸다. KIS는 멱등 키가 없어 이중 주문이 그대로 난다. 기각.
- **B. Toss 재요청 확정 + KIS 목록 매칭** — 증권사마다 해소 방식이 다르고, Toss 쪽은 "늦은 주문" 위험을 진다.
- **C. 두 증권사 모두 "조회만" — 당일 주문 목록에서 매칭** — 시스템이 사용자가 보지 못한 주문을 새로 내는 일이 없다. 매칭이
  모호하면 사람이 본다.

## Decision

**C.** 사용자 결정(2026-10-04): Toss 재요청은 쓰지 않는다. 결과 불명 중에는 같은 종목·같은 방향 새 주문을 차단한다.

### 1. 주문 의도를 브로커 호출 *전에* 커밋한다 — 트랜잭션 3단

```
[tx1 REQUIRES_NEW]  사용자별 advisory lock → 자격증명 → 리스크 게이트 → 중복 가드
                    → INSERT brokerage_orders(status=PENDING_SUBMIT, client_order_id) → COMMIT
[트랜잭션 없음]      client.submitOrder(credentials, request, clientOrderId)       ← DB 커넥션을 잡지 않는다
[tx2 REQUIRES_NEW]  결과 분류에 따라 SUBMITTED / REJECTED / UNKNOWN 으로 갱신 (+ 시장가 체결 동기화)
```

- 크래시가 어디서 나든 흔적이 남는다. tx1 커밋 전이면 **브로커는 호출되지 않았다**(순서 보장). tx1 커밋 후라면
  `PENDING_SUBMIT` 행이 남고 대조 잡이 해소한다.
- 브로커 호출 동안 DB 커넥션을 잡지 않는다 — 주문 경로 커넥션 풀 고갈(resilience L-05)을 악화시키지 않는다.
- `PENDING_SUBMIT` 행도 `brokerage_orders`의 행이므로 **시간당 주문 수 리스크 룰에 바로 잡힌다**(Context 5 해결).
- 트랜잭션 경계는 `TransactionTemplate(REQUIRES_NEW)`로 연다 — 같은 빈 안의 자기 호출이라 `@Transactional`이 걸리지
  않는 함정(ADR-011 Note)을 피한다. 호출자가 트랜잭션 안에서 불러도 tx1은 독립 커밋된다.

### 2. 사용자별 직렬화 + 중복 가드

tx1 첫 줄에서 `pg_advisory_xact_lock(<brokerage-order 네임스페이스>, userId)`. 같은 사용자의 주문 준비가 직렬화된다.
그 안에서 같은 사용자·같은 종목·같은 방향의 `PENDING_SUBMIT`/`UNKNOWN` 주문이 있으면 **409로 거부**한다
("확인 중인 주문이 있습니다"). 조건부 주문·리밸런싱도 같은 경로라 같은 규칙을 탄다.

락은 tx1 동안만 잡힌다(브로커 호출 동안은 아님). 그래서 리스크 게이트의 TOCTOU(사고실험 5)를 **완전히는** 막지 못한다 —
두 주문이 서로 다른 종목이면 둘 다 같은 잔고 스냅샷을 볼 수 있다. 다만 같은 종목·방향의 동시 제출은 완전히 막는다.
잔고 스냅샷에서 미체결 주문 금액을 빼는 것은 후속 작업이다.

### 3. 제출 결과를 세 가지로 나눈다

| 분류 | 의미 | 증권사에 접수됐나 | 상태 |
|---|---|---|---|
| `ACCEPTED` | 2xx + 주문번호 | 예 | `SUBMITTED` (→ 체결 동기화) |
| `REJECTED` | 증권사가 정상 응답으로 거절 (KIS `rt_cd≠0`, 4xx) — **또는 요청이 아예 나가지 않음** (서킷 OPEN, 연결 거부, 연결 타임아웃, 429) | **아니오** | `REJECTED` |
| `INDETERMINATE` | 요청은 나갔는데 확정 응답이 없다 (읽기 타임아웃, 5xx, 408, 2xx인데 주문번호 없음·본문 파싱 실패, 그 외 I/O) | **모름** | `UNKNOWN` |

예외 분류는 `RestClientException`의 원인 체인으로 한다. JDK HttpClient 기준 `HttpConnectTimeoutException`·`ConnectException`은
연결 전 실패라 미전송이고, 그 외 `HttpTimeoutException`은 응답 대기 중 시간 초과라 불명이다. **분류가 애매하면 불명으로
보낸다** — 불명을 거부로 잘못 읽으면 이중 주문이고, 거부를 불명으로 잘못 읽으면 대조 잡이 한 번 더 확인할 뿐이다.

증권사에 `clientOrderId`를 보낸다(Toss). 조회 키로는 못 쓰지만, 우리 HTTP 계층이나 미래의 재시도가 같은 요청을 두 번
보내더라도 Toss가 하나로 합친다. 형식: `mt-<uuid 32자>`(≤36자, `[a-zA-Z0-9_-]`). 조건부 주문은 결정적으로
`co-<conditionalOrderId>`. `brokerage_orders.client_order_id`에 유니크 인덱스 — 같은 조건부 주문이 두 경로로 발동돼도
두 번째 INSERT가 실패한다.

### 4. 대조 잡 — 당일 주문 목록에서 매칭, 절대 재주문하지 않는다

`BrokerageOrderReconciler`가 30초마다 `UNKNOWN`과 **30초 넘은** `PENDING_SUBMIT`(제출 중 크래시)을 처리한다. 30초 미만
`PENDING_SUBMIT`은 아직 호출 중일 수 있어 건드리지 않는다(브로커 읽기 타임아웃 5s + 여유).

```
lookup = client.findOrders(credentials, date, symbol, side)    // 실패 → null, 없음 → []
null            → 그대로 둔다(attempt+1). "조회 실패"를 "주문 없음"으로 읽지 않는다.
candidates = lookup.filter {
    quantity 같음 && (LIMIT이면 가격 같음)
    && orderedAt ∈ [intent 생성 − 5s, intent 생성 + 60s]
    && brokerOrderId가 이 계좌의 다른 brokerage_orders.pg_order_id에 없음
}
1건   → 연결(pg_order_id, broker_order_ref) + 증권사 상태 반영(SUBMITTED/FILLED/…) — resolved_by=BROKER_LOOKUP
0건   → intent 생성 후 2분이 지났으면 REJECTED("증권사 미접수 확인"), 아니면 대기(목록 반영 지연)
2건+  → UNKNOWN 유지, needs_review=true, 경보 — 같은 종목·방향·수량 주문을 HTS 등에서 동시에 낸 경우
```

- 여러 api 레플리카가 동시에 돌아도 같은 행을 두 번 처리하지 않도록 행을 `FOR UPDATE SKIP LOCKED`로 가져간다.
- 한 번에 최대 20건. 증권사 조회는 행 락을 잡은 트랜잭션 안에서 한다(조회 1건 ≤5s, 저빈도라 허용) — 후속 개선 여지.
- 사용자가 `POST /orders/{id}/sync`를 부르면 그 주문 하나를 즉시 대조한다.

### 5. 조건부 주문 — `TRIGGERED` 리퍼

평가기는 `submitOrder(..., clientOrderId = "co-<id>")`로 제출하고, 주문이 `UNKNOWN`이면 조건부 주문을 `TRIGGERED`로
두고 `executed_order_id`만 연결한다. 리퍼(대조 잡과 같은 주기)는 2분 넘은 `TRIGGERED`를 본다:

| `client_order_id = co-<id>` 주문 행 | 조건부 주문 |
|---|---|
| 없음 | tx1이 커밋되지 않았다 → **브로커는 호출되지 않았다** → `FAILED`("발동 중 중단 — 주문 미전송") |
| `SUBMITTED`/`FILLED`/`PARTIALLY_FILLED` | `EXECUTED` |
| `REJECTED`/`CANCELLED` | `FAILED` |
| `PENDING_SUBMIT`/`UNKNOWN` | 그대로 — 대조 잡이 주문을 해소하면 다음 주기에 따라온다 |

"행이 없으면 미전송"이 성립하는 건 §1의 순서(커밋 후 호출) 덕이다.

### 6. 관측

- `brokerage_order_submit_total{provider, outcome=accepted|rejected|indeterminate}`
- `brokerage_order_reconcile_total{result=matched|not_found|ambiguous|lookup_failed}`
- `brokerage_order_unresolved` 게이지 — 5분 넘은 `UNKNOWN`/`PENDING_SUBMIT` 수. **0이 아니면 Page**(실제 돈의 상태를 모른다).

## Reasons

- **실패 방향이 안전하다.** 애매하면 불명 → 대조 → 사람. 이중 주문으로 실패하는 경로가 없다.
- **시스템이 사용자가 확인하지 못한 주문을 새로 내지 않는다**(Toss 재요청 기각). 두 증권사가 같은 해소 경로를 쓴다.
- **크래시 지점마다 흔적이 정해져 있다.** "행 없음 = 미전송"이라는 단순한 판정이 리퍼를 가능하게 한다.
- 중복 가드가 이중 주문의 가장 흔한 경로("실패한 줄 알고 다시 누르기")를 직접 막는다.

## Consequences

- **휴리스틱 매칭이다.** 사용자가 같은 종목·방향·수량을 같은 1분 안에 HTS로도 냈다면 모호해진다 → 사람이 본다. 반대로
  HTS 주문 1건만 있고 우리 주문은 실제로 미도달이었다면 그 HTS 주문에 **잘못 연결될 수 있다**. 창(−5s/+60s)과 수량·가격
  일치로 확률을 낮출 뿐 0은 아니다. 이 오연결은 "체결된 주문을 우리 것으로 표시"하는 방향이라 이중 주문은 일으키지 않는다.
- **KIS 목록 조회는 첫 페이지만 본다**(연속조회 키 `CTX_AREA_*` 미구현). 하루 주문이 페이지 크기를 넘는 사용자는 오래된
  주문이 빠질 수 있다 — 창이 1분이라 실제로는 최근 주문만 필요하지만, 정렬 순서를 실계좌로 확인해야 한다.
- **결과 불명 동안 같은 종목·방향 주문이 막힌다.** 보통 1~2분. 증권사 조회 장애가 길어지면 길어진다 — 이건 의도다
  (상태를 모를 때 주문을 더 쌓지 않는다). 조건부 주문(스탑로스)도 막힐 수 있다.
- **트랜잭션이 3개로 나뉘었다.** tx1과 tx2 사이에서 다른 요청이 `PENDING_SUBMIT` 행을 볼 수 있다(주문 목록에 잠깐 "제출 중"으로).
- 브로커 클라이언트 인터페이스가 바뀌었다(`clientOrderId` 인자, `findOrders`, `SubmitOutcome`). Mock도 같이 바뀐다.
- 대조 잡이 api 레플리카마다 30초마다 돈다 — `SKIP LOCKED`로 중복 처리는 없지만 빈 조회는 레플리카 수만큼 일어난다.
- **실계좌로 검증되지 않았다.** KIS `ord_tmd`·`ord_gno_brno`, Toss `orderedAt` 필드 의미는 스펙 기준이다. 모의투자
  E2E([launch-plan Phase 6](../launch-plan.md)) 때 `bench/chaos/kis-stub.py`로 읽기 타임아웃을 주입해 확인할 것(CH-06 확장).

## Revisit When

- 증권사가 클라이언트 주문 ID로 조회를 지원하게 되면(Toss `Order`에 `clientOrderId` 추가 등) — 휴리스틱을 버리고 키 조회로.
- `needs_review`가 실제로 발생하기 시작하면 — 사용자에게 "이 중 어느 주문입니까" 선택 UI를 줄지.
- 대조 잡의 증권사 조회를 트랜잭션 밖으로 빼야 할 만큼 결과 불명이 잦아지면.
- 리스크 게이트 TOCTOU를 완전히 막아야 할 때 — 잔고 스냅샷에서 미체결·불명 주문 금액을 차감한다.
