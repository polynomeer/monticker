# 시세 파이프라인의 지연-정확성 트레이드오프

## 1. 문제 정의

실시간 시세 시스템에서 "지연(latency)"과 "정확성(consistency)"은 구조적으로 긴장 관계에 있다.

**정확성**을 높이려면 서버가 상태 변경을 즉시 클라이언트에 전달해야 한다. 이는 상태 전체를 매번 전송하거나(full snapshot), 변경분만 전송하는(delta) 두 가지 방법으로 구현할 수 있다. 전자는 클라이언트 상태가 항상 서버와 일치하지만 전송량이 크고, 후자는 전송량이 작지만 클라이언트가 초기 상태를 어딘가에서 받아야 하며 중간 메시지를 놓치면 상태가 어긋난다.

**지연**을 낮추려면 연결을 유지하고 서버가 변경을 즉시 푸시해야 한다. 그러나 연결 유지 자체에는 비용이 따른다. WebSocket 연결이 끊기면 재연결 시 갭(gap)이 생기고, 이 갭을 메우는 비용은 시스템 복잡도를 높인다.

monticker의 스크리너는 수십~수백 개 종목의 가격을 동시에 표시한다. 이 환경에서는 다음 두 축이 교차한다.

- **전송 효율**: 전체 목록을 반복 전송하는 것은 대역폭 낭비다.
- **상태 일관성**: 가격만 푸시할 때 클라이언트가 이전 종가(prevClose)를 모르면 등락률을 계산할 수 없다.

---

## 2. 선택지 비교

### 순수 WebSocket (full state over WS)

모든 상태를 WebSocket 메시지로 전달한다. 클라이언트는 서버 메시지만으로 UI를 구성할 수 있다.

**장점**
- 클라이언트 상태 모델이 단순하다. 메시지를 받을 때마다 덮어쓰면 된다.
- REST 엔드포인트와 WS 메시지 간 불일치가 없다.

**단점**
- 전송량이 크다. 스크리너 전체 목록(예: 100종목 × 20필드)을 매 틱마다 전송하면 네트워크 비용이 급증한다.
- 재연결 시 서버가 즉시 전체 스냅샷을 재전송해야 한다. 이를 위한 세션 관리 또는 스냅샷 캐시가 필요하다.
- 연결이 없는 동안 쌓인 메시지를 처리하는 메시지 큐 또는 재전송 로직이 필요하다.

### 순수 REST 폴링

일정 간격으로 REST API를 호출한다. 현재 `useStockPrice`의 fallback 구현이 이 방식이다.

```typescript
// useStockPrice.ts (현재 구현)
const interval = setInterval(() => {
  fetch(`/api/stocks/${stockId}/price`)
    .then((r) => r.json())
    .then((data) => {
      if (data.hasData) setPrice(data);
    });
}, 3000);
```

**장점**
- 구현이 단순하다. 클라이언트에 재연결 로직이 없다.
- 서버 상태 비저장(stateless). 부하 분산이 쉽다.

**단점**
- 지연이 폴링 주기에 종속된다. 3초 폴링 시 평균 지연은 1.5초다.
- 폴링 주기를 줄이면 서버 부하가 비례하여 증가한다. 100종목을 1초마다 폴링하면 100 req/s가 된다.
- 가격 변동이 없는 구간에도 요청이 발생한다.

### 채택: REST 초기 스냅샷 + WebSocket 델타 패치

mount 시 REST로 현재 상태를 가져오고, 이후 변경분만 WebSocket으로 수신한다.

**근거**
- 초기 스냅샷을 REST로 받으면 클라이언트가 prevClose 등 계산에 필요한 모든 필드를 확보한다.
- 이후 WS 메시지는 `stockId + price + volume + timestamp` (~50바이트)만 전송하면 충분하다.
- 재연결 시 다시 REST 스냅샷을 호출하면 갭이 자동으로 메워진다. 서버 측에 별도 세션 관리가 불필요하다.
- `PriceBroadcaster`는 스냅샷 전달에 관여하지 않는다. 단순히 `PriceTick`을 수신하여 STOMP 토픽에 게시하는 단일 책임을 유지한다.

---

## 3. 전송량 정량 분석

### 시나리오 A: 스크리너 전체 목록을 WS로 10초마다 전송

스크리너 종목 1건에 포함되는 필드의 예시:

| 필드 | 예시 값 | 크기(UTF-8) |
|------|---------|------------|
| stockId | 12345 | 5 B |
| symbol | "SAMSUNG" | 8 B |
| name | "삼성전자" | 12 B |
| price | "78500.00" | 9 B |
| prevClose | "77800.00" | 9 B |
| volume | 15230000 | 9 B |
| changeRate | "+0.90" | 7 B |
| marketCap | 469000000000000 | 16 B |
| sector | "반도체" | 9 B |
| rank | 1 | 1 B |
| JSON 오버헤드(키, 괄호, 쉼표) | — | ~80 B |

종목 1건 ≈ 165 B, JSON 직렬화 후 평균 약 **200 B** 가정.

| 종목 수 | 메시지 크기 | 10초 주기 시 분당 전송량 |
|---------|------------|----------------------|
| 50종목  | 10 KB      | 60 KB/min            |
| 200종목 | 40 KB      | 240 KB/min           |
| 500종목 | 100 KB     | 600 KB/min           |

클라이언트가 10명이면 500종목 기준 분당 **6 MB**가 서버 아웃바운드 대역폭으로 소비된다.

### 시나리오 B: 가격만 WS 패치 (채택 방식)

`PriceBroadcaster`가 전송하는 메시지 구조:

```kotlin
val message = mapOf(
    "type"      to "PRICE_UPDATED",  // 14 B
    "stockId"   to tick.stockId,     // 5 B
    "symbol"    to tick.symbol,      // 8 B
    "price"     to tick.price,       // 9 B
    "volume"    to tick.volume,      // 9 B
    "timestamp" to tick.tradeTime,   // 24 B
)
```

JSON 오버헤드 포함 약 **100 B**, 보수적으로 **150 B** 가정.

| 활성 종목 수 | 틱 빈도 | 분당 메시지 수 | 분당 전송량 |
|------------|---------|------------|------------|
| 50종목, 1틱/10s | — | 300       | 45 KB/min  |
| 200종목, 1틱/5s | — | 2,400     | 360 KB/min |
| 500종목, 1틱/1s | — | 30,000    | 4.5 MB/min |

클라이언트가 `/topic/market` 하나를 구독하면 모든 종목의 가격 업데이트를 단일 채널로 수신한다. 종목 상세 페이지는 `/topic/stocks/{id}`만 구독하여 불필요한 트래픽을 차단한다.

### 비교 요약

| 방식 | 200종목, 10s 갱신, 10클라이언트 기준 분당 총량 |
|------|----------------------------------------------|
| Full snapshot over WS | 240 MB/min |
| 델타 패치 (채택) | 3.6 MB/min |
| **절감률** | **약 98%** |

---

## 4. 구현 상세

### `useStockPrice`: mount 시 REST 스냅샷 → WS 구독 전환

현재 코드는 REST 폴링 fallback으로 구현되어 있다. 설계 의도는 다음과 같다.

```typescript
export function useStockPrice(stockId: number, initialPrice?: PriceData) {
  const [price, setPrice] = useState<PriceData | null>(initialPrice ?? null);

  useEffect(() => {
    // 1단계: mount 시 REST 스냅샷으로 초기 상태 확보
    fetch(`/api/stocks/${stockId}/price`)
      .then((r) => r.json())
      .then((data) => { if (data.hasData) setPrice(data); });

    // 2단계 (구현 예정): STOMP 클라이언트로 /topic/stocks/{id} 구독
    // const client = new Client({ brokerURL: 'ws://host/ws' });
    // client.subscribe(`/topic/stocks/${stockId}`, (msg) => {
    //   const tick = JSON.parse(msg.body);
    //   setPrice(tick);
    // });
  }, [stockId]);
}
```

`initialPrice` prop을 받는 것은 서버 컴포넌트(SSR)가 최초 렌더 시 HTML에 가격을 포함시킬 수 있도록 하는 인터페이스다. SSR에서 내려온 값을 초기 상태로 사용하면 WS 연결이 완료되기 전에도 사용자에게 의미 있는 데이터를 보여줄 수 있다.

### `PriceBroadcaster`: 두 토픽 분리 이유

```kotlin
messagingTemplate.convertAndSend("/topic/stocks/${tick.stockId}", message)
messagingTemplate.convertAndSend("/topic/market", message)
```

`/topic/stocks/{id}`는 종목 상세 페이지 전용이다. 해당 종목의 틱만 수신하므로 구독자별 수신 메시지 수가 최소화된다.

`/topic/market`은 스크리너/홈 화면 전용이다. 모든 종목의 가격 변동이 하나의 채널로 흐르므로, 스크리너는 구독 수 1개로 전체 종목의 가격 갱신을 받는다. 종목이 100개일 때 구독 100개를 유지하는 것보다 훨씬 효율적이다.

두 토픽에 동일한 메시지를 게시하는 것은 중복처럼 보이지만, 각각 독립적인 구독 집합을 가지므로 메시지 팬아웃은 STOMP 브로커(Spring의 `SimpleBroker`)가 처리한다.

---

## 5. 재연결 처리

WebSocket은 네트워크 불안정, 프록시 타임아웃, 모바일 환경에서의 백그라운드 전환 등으로 예고 없이 끊긴다.

설계된 재연결 흐름:

```
WS 연결 끊김
    │
    ▼
reconnectDelay 5초 대기
(이 구간에서 틱 손실 발생)
    │
    ▼
REST GET /api/stocks/{id}/price 호출
(Redis 캐시에서 최신 값 반환)
    │
    ▼
WS 재연결 완료
    │
    ▼
/topic/stocks/{id} 재구독
```

핵심은 재연결 성공 직후 REST 스냅샷을 다시 호출하는 것이다. 이 한 번의 호출로 WS가 끊긴 동안의 갭이 메워진다. 서버 측에서는 Redis에 최신 가격이 항상 캐시되어 있으므로(`RedisPriceCache`) 별도의 이벤트 재생(event replay) 로직이 불필요하다.

STOMP 클라이언트 라이브러리(`@stomp/stompjs`)는 `reconnectDelay` 옵션과 `onConnect` 콜백을 제공한다. 재연결 직후 `onConnect`에서 REST 스냅샷 호출을 트리거하는 것이 권장 패턴이다.

---

## 6. 한계와 향후 과제

### 장 시간(09:00~15:30) 처리

현재 구현은 장 마감 여부를 구분하지 않는다. 장 외 시간에도 폴링 타이머가 동작하며 서버에 불필요한 요청을 보낸다. 서버 응답에 `marketOpen: boolean` 필드를 포함하거나, 클라이언트가 KST 기준으로 장 시간을 판별하여 폴링 주기를 늘리는 것이 필요하다.

### 지연 측정 부재

현재 파이프라인 어느 단계에서도 end-to-end 지연을 측정하지 않는다. 거래소 시세 수신 → Redis 캐시 갱신 → WS 브로드캐스트 → 클라이언트 렌더링까지의 총 지연을 알 수 없다. OpenTelemetry tracing이 도입되어 있으나(`b42da9a`), WS 메시지 경로는 span으로 포함되지 않는다.

### WS 없는 클라이언트 대응

현재 `useStockPrice`의 WS 미구현 구간은 3초 폴링으로 동작한다. WS 구독으로 전환된 이후에도 WS를 지원하지 않는 환경(일부 기업 프록시)을 위한 Long Polling 또는 SSE fallback을 검토해야 한다.
