# ADR-028: 증권사 주문 취소를 실제로 브로커에 전달

## Status
Accepted

## Context

[ADR-026](026-toss-brokerage-integration.md)와 [ADR-027](027-brokerage-credential-refresh.md) 둘 다 같은 결함을 지적만 하고 넘겼다: `BrokerageService.cancelOrder()`가 로컬 `brokerage_orders.status`만 `CANCELLED`로 바꾸고, `BrokerageClient` 인터페이스 자체에 취소 메서드가 없어 KIS/Toss 어느 쪽에도 취소 요청을 보낸 적이 없었다. 화면에는 "취소됨"으로 보여도 증권사 쪽에서는 원주문이 그대로 살아 있다가 체결될 수 있는, 실거래에서 방치할 수 없는 결함이다.

**KIS 취소 API 검증** (python-kis 참조 구현체로 교차검증, `docs/decisions/025`와 동일한 방식):
- 엔드포인트: `POST /uapi/domestic-stock/v1/trading/order-rvsecncl` (주문 제출과 같은 계열), TR_ID `TTTC0803U`(실전)/`VTTC0803U`(모의).
- Body: `CANO`/`ACNT_PRDT_CD`(계좌), `KRX_FWDG_ORD_ORGNO`(지점코드), `ORGN_ODNO`(원주문번호), `RVSE_CNCL_DVSN_CD=02`(취소, 01은 정정), `QTY_ALL_ORD_YN=Y`(전량 취소).
- **`KRX_FWDG_ORD_ORGNO`(지점코드)는 계좌번호에서 계산할 수 없다** — 원주문 제출(`order-cash`) 응답에만 있고, 취소할 때 그대로 다시 넘겨야 한다. 즉 주문 제출 시점에 이 값을 받아 저장해두지 않으면 나중에 취소 자체가 불가능하다.
- **주문 제출/취소(`order-cash`/`order-rvsecncl`) 응답의 `output` 필드명은 대문자다** (`ODNO`, `KRX_FWDG_ORD_ORGNO`) — python-kis의 `KisString["ODNO"]`로 확인. 반면 일별체결조회/잔고조회 응답은 소문자다(`odno`, `pdno`, `hldg_qty` 등, 기존 코드가 이미 맞게 씀). **기존 `KisBrokerageClient`의 주문 제출 응답 파싱(`KisOrderOutput.odno`, 소문자)이 이 불일치 때문에 실제 KIS 서버에서는 항상 null로 바인딩되고 있었다** — Jackson/Kotlin은 대소문자를 구분하고, 이 프로젝트는 case-insensitive 매핑을 켜두지 않았다. `MockBrokerageClient`만 써온 지금까지는 드러나지 않았던 버그다. 취소 기능을 구현하려면 이 필드를 어차피 정확히 읽어야 해서 이번에 같이 고쳤다.

**Toss 취소 API**: [ADR-026](026-toss-brokerage-integration.md) 조사 당시 이미 확인한 `POST /api/v1/orders/{orderId}/cancel` — `orderId`와 표준 헤더(Authorization, X-Tossinvest-Account)만 있으면 되고, KIS의 지점코드 같은 추가 참조값이 필요 없다. 응답의 `orderId`는 취소 요청 자체의 새 식별자로 원주문 orderId와 다르다(스펙에 명시) — 원주문 상태는 이후 `getOrderStatus()`로 별도 확인한다.

## Decision

1. **`BrokerageClient`에 `cancelOrder(credentials, pgOrderId, brokerOrderRef): BrokerageCancelResult` 추가.** `BrokerageCancelResult(cancelled: Boolean, reason: String?)` — 실패해도 예외를 던지지 않고 결과값으로 알려준다(기존 `submitOrder`/`getOrderStatus`와 같은 컨벤션).

2. **`BrokerageOrderResult`에 `brokerOrderRef: String?` 추가** — KIS는 주문 제출 응답의 `KRX_FWDG_ORD_ORGNO`를 여기 담아 돌려주고, `BrokerageOrder` 엔티티의 신규 컬럼(`broker_order_ref`)에 저장해뒀다가 취소 시 그대로 넘긴다. Toss/Mock은 필요 없어 항상 null.

3. **KIS 주문 제출 응답 파싱 수정** — `KisOrderOutput`에 `@JsonProperty("ODNO")`/`@JsonProperty("KRX_FWDG_ORD_ORGNO")`를 붙여 대문자 필드를 정확히 매핑한다.

4. **`BrokerageService.cancelOrder()`가 실제로 브로커를 호출** — 로컬 상태 검증(`SUBMITTED`인지) 통과 후 `client.cancelOrder(...)`를 호출하고, 실패하면 로컬 상태를 바꾸지 않고 예외를 던진다. 즉 증권사가 거부하면(이미 체결됨 등) 화면에도 취소되지 않은 것으로 정확히 보인다.

## Reasons

- **`BrokerageCancelResult`로 실패를 표현(예외 대신)**: KIS/Toss 클라이언트 내부에서는 이미 서킷브레이커/네트워크 오류를 결과값으로 흡수하는 컨벤션(`submitOrder`가 REJECTED를 예외 대신 결과로 주는 것과 동일)을 따른다 — `BrokerageService`가 한 곳에서 성공/실패를 판단하게 한다.
- **`brokerOrderRef`를 주문 시점에 저장**: 지점코드는 계좌번호로 계산 불가능하고 원주문 응답에만 있다 — 나중에 다시 조회할 방법이 없으므로 반드시 제출 시점에 캡처해야 한다. ADR-026의 `providerAccountRef`(Toss accountSeq)와 같은 패턴이다.
- **KIS 필드 대소문자 버그를 같이 고친 이유**: 취소 기능이 정확히 이 필드(`KRX_FWDG_ORD_ORGNO`)에 의존하므로 분리해서 넘길 수 없었다 — 취소를 구현하려면 어차피 주문 제출 응답 파싱부터 고쳐야 했다.

## Consequences

- `brokerage_orders`에 `broker_order_ref` 컬럼이 추가된다(V37). 기존 행은 모두 null — 이 마이그레이션 이전에 체결된 KIS 주문은 어차피 지점코드를 저장한 적이 없어 취소가 원천적으로 불가능하지만, 실사용자가 아직 없는 MVP 단계라 영향 없다.
- KIS 주문 제출 응답의 `ODNO` 파싱 수정은 되돌릴 수 없는 사실 확인이다 — 이전 코드는 실제 KIS 서버 기준으로 주문번호(`pgOrderId`)를 한 번도 정확히 받아온 적이 없었다는 뜻이고(항상 "UNKNOWN"), 이는 `getOrderStatus()`/취소가 전부 무력화됐었다는 의미다. Mock으로만 검증해온 이번 세션 전체에서 이 버그는 드러나지 않았다.
- Toss/Mock은 인터페이스 변경만으로 충분했다 — Toss는 스펙상 이미 알고 있던 엔드포인트를 추가만 했고, Mock은 인메모리 스토어에 상태만 반영한다.
- 여전히 남은 제약: `SUBMITTED` 상태만 취소 가능하다(부분체결 후 잔량 취소는 범위 밖) — KIS/Toss 둘 다 부분체결 주문의 잔량만 취소하는 것도 지원하지만, `BrokerageOrder` 도메인 모델이 지금 부분체결 잔량을 추적하지 않아 이번엔 다루지 않는다.

## Revisit When

- 부분체결(`PARTIALLY_FILLED`) 주문의 잔량 취소를 지원해야 할 때 — 도메인 모델에 잔량 필드가 필요하다.
- KIS 실계좌로 첫 E2E 검증을 실행할 때 — 이번에 고친 `ODNO`/`KRX_FWDG_ORD_ORGNO` 대소문자 수정이 실제로 맞는지 최종 확인해야 한다.
