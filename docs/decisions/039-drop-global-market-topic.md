# ADR-039: `/topic/market` 전역 브로드캐스트 폐지 — 시장 요약 1Hz + 가시 종목 구독

## Status
Accepted

## Context

[`PriceBroadcaster`](../../backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/PriceBroadcaster.kt)는
틱 하나를 **두 곳에** 발행한다:

```kotlin
messagingTemplate.convertAndSend("/topic/stocks/${tick.stockId}", message)
messagingTemplate.convertAndSend("/topic/market", message)          // ← 전역
```

`/topic/market`은 유니버스 전체의 모든 틱이 흐르는 단일 토픽이다. 구독자 1명이 늘어날
때마다 **전체 틱 레이트만큼** 메시지가 늘어난다.

| 규모 | 틱 레이트 | 동접 | `/topic/market` 발송량 |
|------|----------|------|----------------------|
| 현재(T0) | ~200/s | ~10 | 2,000 msg/s |
| T1 | 3,000/s | 5,000 | **15,000,000 msg/s** |
| T2 | 30,000/s | 200,000 | **6,000,000,000 msg/s** |

T1에서 이미 성립하지 않는다.

**실제 구독자는 두 곳이고, 둘의 요구가 서로 다르다:**

1. [`useMarketPricesWs.ts:28`](../../apps/web/src/hooks/useMarketPricesWs.ts#L28) —
   받은 틱을 `Record<stockId, MarketPrice>`에 누적한다.
   유일한 사용처는 [`MarketSummary.tsx:8`](../../apps/web/src/components/home/MarketSummary.tsx#L8)
   (홈 대시보드 위젯)이고, **누적된 종목을 최근 12개로 잘라서 쓴다.**
   → 실제 요구는 "시장 전체의 요약"이지 "모든 종목의 모든 틱"이 아니다.
   전역 토픽에서 우연히 먼저 도착한 12개를 보여주고 있을 뿐이라, 지금도 **표시되는 종목이
   비결정적**이다.

2. [`useScreener.ts:112`](../../apps/web/src/hooks/useScreener.ts#L112) —
   받은 틱의 `stockId`가 현재 목록에 있으면 그 행의 가격만 패치하고, 없으면 버린다.
   → 실제 요구는 "지금 화면에 있는 20~50개 종목"이다. 나머지는 전부 낭비다.

즉 전역 토픽은 **두 개의 서로 다른 요구를 어느 쪽에도 맞지 않는 방식으로** 충족하고
있었다. 스크리너는 필요 없는 것의 99%를 받아서 버리고, 홈 위젯은 필요한 집계를 못 받아서
클라이언트에서 임의로 잘라내고 있다.

## Decision

`/topic/market`을 삭제하고, 두 요구를 각각의 경로로 나눈다.

### 1. `/topic/market/summary` — 시장 요약, 1초 1회

`worker-market`이 1초 주기로 시장 요약을 계산해 Kafka `market.summary`로 발행하고,
브로드캐스트 컨슈머가 STOMP로 릴레이한다.

```
worker-market @Scheduled(fixedRate = 1000)
  summary = {
     지수(KOSPI/KOSDAQ/NASDAQ), 상승/보합/하락 종목 수,
     거래대금 상위 10, 등락률 상위 10, 하위 10
  }                                  ← Redis ZSET(ADR 예정, scale-out-plan §6.4.3)에서 읽는다
  redis.set("market:summary", json, ttl = 5s)     ← REST GET /api/market/summary 폴백용
  kafka.send("market.summary", json)
      └─ 브로드캐스트 컨슈머 → convertAndSend("/topic/market/summary", json)
```

발송량은 **구독자 수 × 1 msg/s**로 고정된다. 틱 레이트와 무관해진다.

`MarketSummary` 컴포넌트는 `useMarketPricesWs` 대신 `useMarketSummaryWs`를 쓰고,
"우연히 잡힌 12개"가 아니라 **서버가 정한 상위 종목**을 표시한다.

### 2. 스크리너는 화면에 보이는 종목만 개별 구독

`useScreener`가 `/topic/market` 하나를 구독하는 대신, 현재 렌더링된 행의
`/topic/stocks/{id}`를 구독한다. 정렬·필터·스크롤로 목록이 바뀌면 구독을 교체한다.

```
visibleStockIds 변경 시:
   해제할 것 = 이전 구독 - 현재 가시 종목
   추가할 것 = 현재 가시 종목 - 이전 구독
```

스크리너는 이미 TanStack Virtual로 **500행 중 ~17행만 DOM에 그린다**
([architecture.md](../architecture.md) Tech Stack). 구독 범위를 DOM 범위에 맞추는 것뿐이다.

### 3. 프론트엔드 전환을 같은 작업 단위에서 끝낸다

서버에서 `/topic/market` 발행을 제거하는 커밋과 프론트엔드 2개 훅을 바꾸는 커밋은
**같은 PR에 포함한다.** [ADR-033](033-remove-netty-broadcast-gateway.md)이 남긴 교훈
("클라이언트 없는 서버 경로를 만들어놓고 방치했다")을 반대 방향으로도 적용한다 —
서버 없는 클라이언트 경로도 만들지 않는다.

## Reasons

- **전역 토픽은 구독자 수와 데이터 레이트를 곱한다.** 이건 튜닝으로 완화되는 종류의
  문제가 아니라 구조적으로 잘못된 fan-out 형태다. conflation
  ([ADR-038](038-broadcast-consumer-partition-assignment.md))을 걸어도 전체 종목 × 10Hz는
  여전히 감당 불가다.
- **두 소비자의 요구를 실제로 읽어보니 둘 다 전역 스트림이 필요 없었다.** 하나는
  집계, 하나는 가시 영역. 전역 토픽은 둘 중 어느 것도 제대로 주지 못하고 있었다.
- **집계를 서버로 옮기면 부수적으로 버그가 고쳐진다.** `MarketSummary`가 지금 보여주는
  12개 종목은 "먼저 틱이 온 순서"라 새로고침할 때마다 달라진다. 서버가 거래대금
  상위를 계산해 내려주면 결정적이고, 의미도 있다.
- **가시 영역 구독은 이미 있는 패턴의 확장이다.** `useStockPrice`가
  `/topic/stocks/{id}`를 구독하는 코드가 이미 있고, 스크리너는 DOM 가상화도 이미 하고
  있다. 새 개념을 도입하지 않는다.

## Consequences

- **`useMarketPricesWs` 훅이 사라진다.** `MarketSummary`가 유일한 사용처였다.
- **스크리너의 구독 수가 사용자당 20~50개로 늘어난다.** 전역 토픽 1개보다 STOMP 구독
  관리 비용이 커지지만, 받는 메시지 총량은 100배 이상 줄어든다. 구독/해제가 스크롤마다
  일어나므로 **디바운스(예: 200ms)가 필요**하다 — 빠른 스크롤 중 수백 개의
  SUBSCRIBE/UNSUBSCRIBE 프레임이 나가면 안 된다.
- **시장 요약 계산이 새 워커 작업으로 추가된다.** 1초 주기이므로 부담은 작지만,
  집계 소스가 필요하다. 초기 구현은 기존 `ScreenerRepository` 쿼리를 재사용하고,
  스크리너 ZSET 사전 계산(scale-out-plan §6.4.3, Phase 1)이 들어오면 거기서 읽도록 바꾼다.
- **`market.summary` 토픽이 추가된다** — 파티션 1개로 충분하다
  ([ADR-040](040-kafka-topic-declaration.md)).
- **웹소켓이 끊긴 동안의 요약은 REST로 받는다.** Redis `market:summary`(TTL 5s)를
  `GET /api/market/summary`로 노출해 초기 렌더와 재연결 사이를 메운다.
- 모바일(`apps/mobile`)은 `/topic/market`을 쓰지 않으므로 영향이 없다.

## Revisit When

- 홈 대시보드가 "상위 10개"보다 넓은 시장 데이터를 실시간으로 요구하게 될 때 —
  그때도 전역 틱 스트림이 아니라 **요약의 항목을 늘리는** 방향으로 간다.
- 스크리너에서 200행 이상을 동시에 실시간 갱신해야 하는 요구가 생길 때 —
  개별 구독 대신 "구독 묶음(bundle)" 토픽(예: `/topic/screener/{market}/{sort}`)을
  서버가 conflate해서 내려주는 형태를 검토한다.
