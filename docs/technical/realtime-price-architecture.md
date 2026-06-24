# 실시간 가격 아키텍처

> System Design Document  
> 대상 독자: WebSocket 및 실시간 시스템에 관심 있는 백엔드/풀스택 엔지니어

---

## 1. 요구사항

### 사용자 관점의 "실시간"

주식 스크리너에서 사용자가 기대하는 실시간 경험은 두 가지로 구분된다.

- **순위 정합성**: 거래량 상위 종목 순위가 수십 초 이내로 반영되어야 한다. 순위가 자주 바뀌어도 갑자기 크게 튀어서는 안 된다.
- **가격 체감**: 화면에 표시된 숫자가 살아 있어야 한다. 사용자가 차트나 가격을 응시할 때 1~2초 이내로 변화가 눈에 보여야 한다.

이 두 가지는 **요구하는 갱신 주기와 전송 비용이 서로 다르다**. 순위 갱신은 서버가 전체 목록을 재계산해서 내려줘야 하므로 페이로드가 크고, 잦은 갱신이 오히려 순위 안정성을 해친다. 가격 갱신은 단일 숫자 하나이므로 매초 전송해도 전송량이 적다.

### 기술적 목표

| 항목 | 목표값 |
|------|--------|
| 가격 갱신 지연 | 클라이언트 기준 2초 이내 |
| 순위 갱신 주기 | 10초 (REST 폴링) |
| 브로드캐스트 페이로드 | 종목당 ~150 bytes (가격 메시지) |
| 재연결 후 상태 복구 | 다음 REST 폴링 사이클 내 |

---

## 2. 전체 아키텍처

```
  [Worker 서비스]                     [API 서버]
  ┌───────────────────────────┐       ┌──────────────────────────────────────┐
  │  MarketDataCollector      │       │  PriceBroadcaster                    │
  │  @Scheduled(1초)          │       │  (SimpMessagingTemplate)             │
  │                           │       │                                      │
  │  MockPriceGenerator       │       │  /topic/stocks/{id}  ─── 종목 개별   │
  │        │                  │       │  /topic/market       ─── 전체 마켓   │
  │        v                  │       └──────────────┬───────────────────────┘
  │  RedisTickWriter          │                      │ STOMP over SockJS
  │  stock:price:{market}:{symbol}                   │
  └──────────────┬────────────┘       ┌──────────────┴───────────────────────┐
                 │ Redis SET           │  [브라우저]                          │
                 │                     │                                      │
  ┌──────────────▼────────────┐        │  useScreener                         │
  │  Redis                    │        │  ├── REST 10초 폴링 (순위 갱신)       │
  │  stock:price:*            │        │  └── WS /topic/market (가격 패치)    │
  └──────────────┬────────────┘        │                                      │
                 │ Redis 읽기           │  useStockPrice                       │
  ┌──────────────▼────────────┐        │  └── REST 3초 폴링 (단일 종목)       │
  │  API 서버                 │        └──────────────────────────────────────┘
  │  /api/events/recent       │
  │  /api/screener            │
  │  /api/stocks/{id}/price   │
  └───────────────────────────┘
```

데이터 흐름 요약:

1. Worker가 1초마다 모든 종목의 가격 틱을 생성하여 Redis에 저장한다.
2. API 서버가 Redis에서 틱을 읽어 `PriceBroadcaster`를 통해 WebSocket 토픽으로 브로드캐스트한다.
3. 브라우저는 STOMP 구독을 통해 가격 메시지를 수신하여 화면 상태만 패치한다.
4. 스크리너 순위는 별도로 REST 10초 폴링을 통해 갱신된다.

---

## 3. STOMP over SockJS 선택 이유

### 순수 WebSocket 대비 단점

순수 `WebSocket` API를 사용하면 다음을 직접 구현해야 한다.

- 재연결 로직 (지수 백오프, 최대 재시도 횟수)
- 메시지 라우팅 (어떤 메시지가 어느 핸들러로 가는지 구분)
- 구독 lifecycle 관리 (구독 ID 추적, 구독 취소)

### SockJS가 제공하는 것

SockJS는 WebSocket을 우선 시도하되, 환경에 따라 자동으로 HTTP Streaming → HTTP Long-polling으로 폴백한다. 프록시 뒤에서 WebSocket 업그레이드가 막히는 환경(일부 기업 방화벽, 구형 로드밸런서)에서도 연결이 유지된다.

### STOMP 프로토콜이 제공하는 것

STOMP(Simple Text Oriented Messaging Protocol)는 WebSocket 위에서 동작하는 메시지 라우팅 레이어다.

- `/topic/market` 같은 목적지(destination) 기반 라우팅
- `subscribe` / `unsubscribe` 메시지 단위 구독 관리
- `@stomp/stompjs` 클라이언트의 `reconnectDelay` 옵션으로 자동 재연결

Spring의 `@EnableWebSocketMessageBroker` 어노테이션은 인메모리 STOMP 브로커를 활성화하며, `/topic` 프리픽스가 브로커 토픽으로 라우팅된다. `/app` 프리픽스는 서버측 `@MessageMapping` 핸들러로 향한다.

```kotlin
// WebSocketConfig.kt
registry.enableSimpleBroker("/topic")       // 브로커 토픽
registry.setApplicationDestinationPrefixes("/app")  // 서버 핸들러
```

---

## 4. 서버측 브로드캐스트 설계

`PriceBroadcaster`는 단일 틱을 받아 두 토픽에 동시 발행한다.

```kotlin
fun broadcast(tick: PriceTick) {
    val message = mapOf(
        "type"      to "PRICE_UPDATED",
        "stockId"   to tick.stockId,
        "symbol"    to tick.symbol,
        "price"     to tick.price,
        "volume"    to tick.volume,
        "timestamp" to tick.tradeTime.toString(),
    )
    messagingTemplate.convertAndSend("/topic/stocks/${tick.stockId}", message)
    messagingTemplate.convertAndSend("/topic/market", message)
}
```

### 토픽 분리 이유

| 토픽 | 구독자 | 목적 |
|------|--------|------|
| `/topic/stocks/{id}` | 개별 종목 상세 페이지 | 특정 종목만 정밀 구독. 연결당 트래픽 최소화. |
| `/topic/market` | 스크리너, 대시보드 | 전체 마켓 메시지를 한 토픽에서 수신. |

종목 상세 페이지에서는 `/topic/stocks/{id}` 하나만 구독하면 되므로, 전체 마켓 메시지를 받아 클라이언트에서 필터링하는 낭비가 없다. 반대로 스크리너는 수십~수백 종목이 동시에 갱신되어야 하므로 `/topic/market` 단일 구독으로 모든 틱을 수신한다.

---

## 5. 클라이언트 구독 전략

### useStockPrice — 종목별 구독 (현재 상태)

`useStockPrice`는 현재 STOMP 연결 없이 REST 3초 폴링으로 구현되어 있다. 이는 개발 초기 단계의 fallback이며, 이후 `/topic/stocks/{id}` 구독으로 교체 예정이다.

```typescript
// 현재: REST 3초 폴링
const interval = setInterval(() => {
  fetch(`/api/stocks/${stockId}/price`).then(...);
}, 3000);
```

### useScreener — 전체 토픽 + in-place 패치

`useScreener`는 REST와 WebSocket을 조합한 혼합 전략을 사용한다.

```typescript
// WebSocket 연결 (마운트 시 한 번)
const client = new Client({
  webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe("/topic/market", (msg) => {
      const data = JSON.parse(msg.body);

      setState(p => {
        // 해당 stockId 항목만 가격/등락률/거래량 패치
        const updated = p.items.map(item =>
          item.stockId === data.stockId
            ? { ...item, price: data.price, changeRate, changeAmount, volume: data.volume }
            : item
        );
        // 변경 없으면 동일 참조 반환 → 리렌더 방지
        return updated === p.items ? p : { ...p, items: updated };
      });
    });
  },
});
```

핵심은 **배열 전체를 교체하지 않는다**는 점이다. 수신된 `stockId`와 일치하는 항목만 교체하고, 일치하는 항목이 없으면 기존 참조를 그대로 반환하여 불필요한 리렌더를 차단한다.

---

## 6. REST + WebSocket 혼합 전략

### 왜 랭킹은 REST 10초인가

랭킹을 WebSocket으로 전송하면 두 가지 문제가 생긴다.

**전송량**: 스크리너 목록은 종목당 약 8개 필드(rank, stockId, symbol, price, changeRate, changeAmount, volume, marketCap)를 포함하며, 100종목 기준 약 12 KB 페이로드가 된다. 이를 매초 모든 접속 클라이언트에 전송하면 서버 발신 트래픽이 클라이언트 수에 선형으로 증가한다.

**순위 안정성**: 거래량 기준 순위는 1초 단위로 계산하면 미세한 거래량 차이로 순위가 계속 바뀐다. 이는 사용자가 보는 화면이 계속 재정렬되어 가독성을 해친다.

대안: REST 10초 폴링은 데이터 정합성 있는 순위를 간헐적으로 받고, 가격만 WebSocket으로 매초 패치한다.

| 방식 | 주기 | 페이로드 | 클라이언트 100개 기준 서버 발신 |
|------|------|----------|-------------------------------|
| WS 전체 랭킹 | 1초 | ~12 KB | ~1.2 MB/s |
| REST 폴링 | 10초 | ~12 KB | ~120 KB/s (풀링 응답) |
| WS 가격 패치 | 1초 | ~150 B x N종목 | ~1.5 KB/s (100종목 기준) |

WS 가격 패치는 종목당 단일 숫자이므로 전송량이 압도적으로 적다.

---

## 7. prevClose 캐시와 등락률 재계산

WebSocket으로 수신되는 메시지는 `price`와 `volume`만 포함한다. 등락률(`changeRate`)과 등락금액(`changeAmount`)은 전일 종가(`prevClose`)가 있어야 계산할 수 있다.

서버가 매 틱마다 `prevClose`를 함께 전송하면 메시지 크기가 늘어나고, 모든 클라이언트에 불필요한 고정 데이터를 반복 전송하게 된다. 대신 `useScreener`는 REST 폴링으로 첫 랭킹 데이터를 받을 때 `prevClose`를 클라이언트 메모리에 캐싱한다.

```typescript
// REST 응답에서 prevClose 역산하여 캐시
data.items.forEach((item) => {
  if (item.price && item.changeRate !== undefined) {
    const prevClose = item.changeRate !== 0
      ? item.price / (1 + item.changeRate / 100)
      : item.price;
    prevCloseRef.current[item.stockId] = prevClose;
  }
});
```

이후 WebSocket 메시지가 오면 캐시된 `prevClose`로 등락률을 클라이언트에서 재계산한다. `prevCloseRef`는 React 상태가 아닌 `useRef`로 보관하므로, 캐시 갱신 시 리렌더가 발생하지 않는다.

---

## 8. 재연결과 상태 복구

### STOMP 클라이언트 재연결

`@stomp/stompjs`의 `reconnectDelay: 5000` 설정은 연결이 끊어진 뒤 5초 후 자동으로 재연결을 시도한다. 재연결 성공 시 `onConnect` 콜백이 재호출되어 `/topic/market` 구독이 자동으로 복구된다.

```typescript
onDisconnect: () => setState(p => ({ ...p, wsConnected: false })),
```

연결이 끊긴 동안 `wsConnected: false` 상태가 되어 UI에 연결 해제 상태를 표시할 수 있다. 재연결 전까지 가격은 마지막 수신값이 유지된다.

### 상태 복구 경로

재연결 후 가격은 WebSocket으로 바로 갱신되지만, 순위 정합성은 다음 REST 폴링 사이클(최대 10초)에서 복구된다. 연결 해제 기간이 길었다면 순위가 틀릴 수 있으나, 10초 이내에는 자동 정정된다.

---

## 9. 확장성 고려사항

현재 구현은 Spring 인메모리 브로커(`enableSimpleBroker`)를 사용한다. 단일 API 서버 프로세스 내에서만 동작하므로 다음 제약이 있다.

**현재 구조의 한계**:
- API 서버가 수평 확장(복수 인스턴스)될 때 브로커가 인스턴스별로 분리된다. 클라이언트가 인스턴스 A에 연결되었는데 Worker가 인스턴스 B에 틱을 전송하면 메시지가 전달되지 않는다.

**마이그레이션 경로**:

```
현재:  Worker → Redis → API (인메모리 브로커) → WS → Client

1단계: Worker → Redis Pub/Sub → API (채널 구독) → WS → Client
       (API 인스턴스 수에 무관하게 모든 인스턴스가 동일 메시지 수신)

2단계: Worker → Kafka → API (Consumer Group) → WS → Client
       (틱 순서 보장, 재처리 가능, 파티션별 처리량 확장)
```

Spring은 `enableStompBrokerRelay`를 통해 외부 STOMP 브로커(RabbitMQ, ActiveMQ)로 교체할 수 있다. Redis Pub/Sub 기반 커스텀 릴레이를 만들면 Redis 의존성 추가 없이 현재 인프라를 재활용할 수 있다.

---

## 10. 한계

**`useStockPrice`의 WebSocket 미구현**: 종목 상세 페이지의 `useStockPrice`는 현재 REST 3초 폴링으로 동작한다. STOMP 연결을 추가하면 지연을 1초로 줄일 수 있으나, 아직 구현되지 않았다.

**prevClose 역산의 부정확성**: `changeRate`에서 `prevClose`를 역산하는 방식은 부동소수점 오차가 누적될 수 있다. 서버 API가 `prevClose`를 직접 제공하거나, REST 응답에 포함된 값을 사용하는 것이 더 정확하다.

**인메모리 브로커 단일 인스턴스 제약**: 위 확장성 절에서 설명했듯이, 현재 구조는 API 서버 단일 인스턴스에서만 정상 동작한다. 수평 확장 전에 브로커 외부화가 필요하다.

**WebSocket 인증 없음**: 현재 `/ws` 엔드포인트는 `setAllowedOriginPatterns("*")`로 열려 있으며 JWT 검증이 없다. 사용자 인증이 도입될 경우 STOMP `CONNECT` 프레임의 `Authorization` 헤더 처리가 추가되어야 한다.

**Mock 데이터**: `MarketDataCollector`가 `MockPriceGenerator`를 사용하므로 실제 시장 데이터와 연동되어 있지 않다. 실시간성의 의미가 "시스템 내부 파이프라인"에 한정되며, 실제 거래소 연동 시 Worker 수집 로직 교체가 필요하다.
