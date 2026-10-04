# ADR-055: 실주문은 출처가 확인된 실시세로만 발동한다 — 틱 출처(provenance) 태깅

## Status
Accepted

ADR-030/031(시세 커버리지)과 ADR-032(조건부 주문)를 **보완**한다. 둘 다 그대로 유효하다 — 이 ADR은 두 결정이
만나는 지점에서 빠져 있던 불변식 하나를 명시한다.

## Context

2026-10 시스템 설계 리뷰([design-review-2026-10.md](../design-review-2026-10.md))에서, 따로 보면 각각 옳은
두 결정이 합쳐지면 실제 돈 사고가 나는 경로를 사고실험으로 찾았다.

- **ADR-030/031 (표시용 결정)** — KIS는 21종목, Toss는 미국 전체 + 국내 일부만 실시간으로 덮는다. 나머지 종목은
  `MockPriceGenerator`가 계속 랜덤워크 틱을 만들어 **같은 `market.ticks` 토픽**에 넣는다. 화면에 시세가 비지 않게
  하려는 결정이었고, 그 목적에는 맞다.
- **ADR-032 (실주문 결정)** — `ConditionalOrderEvaluator`가 `market.ticks`의 모든 틱에 반응해 트리거 가격을
  넘으면 `BrokerageService.submitOrder()`로 **실제 증권사 주문**을 낸다.
- **틱에는 출처가 없었다.** 와이어 포맷(worker `GeneratedTick`, Go `tick.Tick`, api `MarketTickMessage`) 어디에도
  실시세인지 합성 시세인지 구분하는 필드가 없었다. 게다가 api 쪽 와이어는 `marketStatus` 기본값이 `"OPEN"`,
  `generatedAt` 기본값이 `Instant.now()`라 필드가 빠진 틱이 "지금 막 생성된 정규장 시세"로 위장됐고, 컨슈머는
  `PriceTick`을 만들면서 그 두 필드마저 버렸다.
- **Mock은 장 마감 후에도 틱을 낸다** (`CLOSED` → `POST_MARKET`, 변동성 0.1배).

결과: 사용자가 KIS/Toss가 덮지 않는 종목에 스탑로스를 걸면, 랜덤워크가 트리거 가격을 넘는 순간 실계좌에서 매도
주문이 나간다. 장 마감 후에도 마찬가지다. ADR-032의 원자적 `UPDATE ... WHERE status='ACTIVE'`는 **중복 발동**을
막을 뿐 **가짜 가격**은 막지 못한다. 리스크 게이트(ADR-025)도 막지 못한다 — 게이트가 보는 `candles_1m`도 같은
합성 틱으로 만들어진다.

고려한 대안:

- **A. 토픽 분리** (`market.ticks.real` / `market.ticks.mock`) — 실주문 컨슈머가 실시세 토픽만 구독한다. 가장 강한
  격리지만, 커버리지가 종목별로 동적으로 바뀌는 구조(ADR-030의 `KisCoverageProvider`)에서 표시·캔들·디텍터
  컨슈머가 두 토픽을 합쳐야 하고, 같은 종목의 순서 보장(키=stockId)이 토픽 사이에서 깨진다.
- **B. 틱에 출처 필드를 싣고, 실주문 소비자가 게이트한다** — 와이어 필드 하나, 소비자 한 곳.
- **C. api가 커버리지 집합을 따로 알고 종목 단위로 막는다** — 커버리지의 단일 진실 소스는 worker에 있다
  (ADR-030). api에 사본을 두면 둘이 어긋나는 순간 같은 사고가 다시 난다.

## Decision

**B.** 틱 한 건이 자기 출처를 들고 다니고, 돈을 움직이는 소비자는 출처를 확인한 뒤에만 행동한다.

### 1. 와이어 포맷에 `source`를 추가한다

| 생산자 | `source` |
|--------|----------|
| worker `KisExecutionTickHandler` (H0STCNT0 실시간 체결) | `KIS` |
| worker `KisPriceProvider` (REST 현재가 폴링) | `KIS` (+ `marketStatus`를 `MarketSchedule`로 채운다 — 이전엔 비어 있어 항상 `OPEN`으로 해석됐다) |
| worker `TossExecutionTickHandler` | `TOSS` |
| worker `MockPriceGenerator` | `MOCK` |
| Go `market-gateway` | `MOCK` (합성 생성기뿐이다) |

worker `GeneratedTick.source`의 **기본값은 `MOCK`이다.** 새 실시세 생산자가 출처를 빠뜨리면 그 틱으로는 실주문이
나가지 않는다 — 시세가 안 움직이는 쪽(발견하기 쉬움)으로 실패하고, 가짜 가격으로 돈이 나가는 쪽(되돌릴 수 없음)으로
실패하지 않는다.

### 2. api는 출처를 이벤트까지 운반한다

```kotlin
data class MarketTickReceivedEvent(val tick: PriceTick, val provenance: TickProvenance)  // provenance 기본값 없음
data class TickProvenance(val source: PriceSource, val marketStatus: String?, val generatedAt: Instant)
```

`MarketTickMessage`에서 `marketStatus`·`generatedAt`·`source`가 빠지면 각각 `null`·`EPOCH`·`UNKNOWN`이다 —
"정규장 실시세"로 위장하던 기본값을 없앴다.

### 3. 실주문 게이트 — 세 조건을 모두 만족할 때만

`ConditionalOrderEvaluator.onTick`이 **DB를 조회하기 전에** `TickProvenance.rejectReasonForRealOrder(now)`를 본다.

| 조건 | 이유 |
|------|------|
| `source ∈ {KIS, TOSS}` | 합성·출처 불명 시세로는 돈을 움직이지 않는다 |
| `marketStatus == "OPEN"` | 장외 틱(실시세라도 시간외 단일가 등)으로 시장가 주문을 내지 않는다 |
| `now − generatedAt ≤ 5s` | ADR-045의 `market.ticks` 컨슈머 랙 SLO(5s). Kafka 장애 복구 직후 밀린 틱이 한꺼번에 들어와 이미 지나간 가격으로 발동하는 것을 막는다 |

거른 틱은 `conditional_order_tick_ignored_total{reason=source|marketStatus|stale}`로 센다.

### 4. api 컨슈머는 관대한 리더(tolerant reader)가 된다

`MarketTickBroadcastConsumer`의 ObjectMapper를 `FAIL_ON_UNKNOWN_PROPERTIES=false`로 바꾼다. 이번 변경으로
생산자(worker·gateway)와 소비자(api)의 배포 순서가 생기는데, 예전 `seq` 필드 때처럼 모르는 필드 하나로
브로드캐스트 전체가 멈추면 안 된다. worker 쪽 `TickKafkaConsumer`는 `GeneratedTick` 자체에 필드가 추가됐으므로
gateway가 먼저 배포돼도 문제없다.

## Reasons

- **불변식이 코드 한 곳에 산다.** "실주문은 실시세로만"이 `TickProvenance` 한 메서드다. 다음에 틱으로 돈을 움직이는
  소비자가 생기면(OTO, 자동 리밸런싱 트리거 등) 같은 메서드를 부른다.
- **fail-closed가 세 겹이다** — 생산자 기본값(`MOCK`), 와이어 누락 시 기본값(`UNKNOWN`/`null`/`EPOCH`), 소비자 게이트.
- **부수 효과로 쿼리가 준다.** 이전에는 합성 틱(초당 수백 종목)마다 `conditional_orders`를 조회했다. 이제
  실시세 틱에만 조회한다.
- 토픽 분리(A)보다 변경 범위가 작고, 표시·캔들·디텍터 경로는 전혀 바뀌지 않는다.

## Consequences

- **커버리지 밖 종목의 조건부 주문은 발동하지 않는다 — 조용히.** 이 ADR 전에는 가짜 가격으로 발동했으니 더
  안전해졌지만, 사용자는 "스탑로스가 걸려 있다"고 믿는다. 이건 별도의 위험이다. 후속: 조건부 주문 **생성 시점**에
  커버리지 밖 종목을 거부하거나 경고해야 한다. 그러려면 worker의 커버리지 집합을 api가 읽을 수 있어야 한다
  (Redis에 게시 등) — [engineering-backlog §2](../engineering-backlog.md#2-조건부-주문-후속-adr-032)에 등록.
- **실시세 피드가 끊기면 조건부 주문도 멈춘다.** KIS 웹소켓이 끊기면 해당 종목은 Mock으로 대체되지 않고
  틱이 끊기므로(커버리지 집합이 기동 시 고정), 끊긴 동안의 가격 이동에는 반응하지 못한다. 재연결 후 첫 틱이
  여전히 조건을 만족하면 그때 발동한다.
- **5초는 정규장 기준이다.** 실시세 틱의 `generatedAt`은 worker가 틱을 만든 시각이라 worker↔api 시계 차이가
  그대로 들어간다. NTP 동기화를 전제한다. 시계가 미래로 어긋난 틱(음수 경과)은 신선한 것으로 본다.
- **정규장 판정이 worker의 `MarketSchedule`에 의존한다.** 휴장일·조기 폐장을 `MarketSchedule`이 모르면 그날은
  `OPEN`으로 나간다 — 다만 그날은 실시세 틱 자체가 오지 않는다.
- 와이어 필드가 하나 늘었다(스키마 레지스트리 없음, ADR-040 Revisit 참고).

## Revisit When

- 틱으로 실주문을 내는 소비자가 두 번째로 생길 때 — 게이트를 이벤트 발행 단계로 올려 "실주문용 틱 이벤트"를 따로
  만들지 검토한다.
- 커버리지가 동적으로 바뀌게 될 때(KIS 다중 커넥션 풀링, 장애 시 Mock 대체 등) — 같은 종목에 두 출처가 섞이는
  구간이 생기므로, 종목별 "현재 권위 있는 출처" 개념이 필요해진다.
- 스키마 레지스트리를 도입할 때 — `source`를 필수 enum 필드로 승격한다.
