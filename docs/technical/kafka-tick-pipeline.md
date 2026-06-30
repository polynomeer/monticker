# Kafka 기반 시세 파이프라인 — Go 게이트웨이 + Netty 브로드캐스트

> 관련 결정: [ADR-005](../decisions/005-kafka-go-gateway-netty-broadcast.md) — 이 변경의 동기는 트래픽이 아니라, monticker가 아직 다루지 않았던 거래소·증권사 시세팀 기술 스택(Kafka, Go, Netty)을 직접 시연하기 위함임을 먼저 밝힌다.

## 1. 전체 그림

```
┌─────────────────────┐
│  Go Market Gateway   │  (services/market-gateway)
│  goroutine per stock │
└──────────┬───────────┘
           │ produce JSON
           ▼
   Kafka topic: market.ticks  (key = stockId, partition별 종목 순서 보장)
           │
     ┌─────┴─────────────────────┐
     ▼                           ▼
┌─────────────┐         ┌──────────────────────┐
│ Kotlin Worker│         │ Netty Broadcast Gateway│
│ (Kafka consumer)│      │ (Kafka consumer)      │
│  → Redis      │         │  → WebSocket clients  │
│  → candles_1m │         └──────────────────────┘
│  → EventDetector
│  → produce ──────────► Kafka topic: market.events
└─────────────┘                    │
                                    ▼
                          Netty Broadcast Gateway
                            → WebSocket clients
```

기존 파이프라인(`MockPriceGenerator` → Redis → 동기 처리)은 그대로 남아 있다. `ingestion.source` 설정으로 두 경로를 전환한다 — Kafka가 떠 있지 않은 로컬 개발 환경에서도 기존 방식으로 동작해야 하기 때문이다.

---

## 2. Go Market Gateway — 왜 Go인가

### 2.1 동시성 모델 비교

```kotlin
// JVM: 종목 1개당 스레드 또는 코루틴
// 코루틴은 가볍지만 여전히 JVM 스케줄러와 GC의 영향을 받는다
```

```go
// Go: 종목 1개당 goroutine
// 시작 스택 크기 2KB, 수만 개를 띄워도 수백 MB 수준
for _, stock := range stocks {
    go generateTicks(stock, producer)
}
```

실거래소 시세 수집기는 보통 종목별·시장별로 별도 커넥션을 유지하며 끊임없이 들어오는 데이터를 받아야 한다. Go의 goroutine은 이런 "수천 개의 동시 I/O 대기" 워크로드에 메모리·컨텍스트 스위칭 비용이 가장 낮다 — 이것이 실제 시세 수집 계층이 Go/C++로 구현되는 흔한 이유다.

### 2.2 구현

```go
// services/market-gateway/main.go
func main() {
    producer := newKafkaProducer(brokers)
    stocks := loadStocks(dbURL)         // PostgreSQL에서 활성 종목 목록 로드

    var wg sync.WaitGroup
    for _, s := range stocks {
        wg.Add(1)
        go func(stock Stock) {
            defer wg.Done()
            runTickLoop(stock, producer)  // 1초 주기로 틱 생성·발행
        }(s)
    }
    wg.Wait()
}
```

```go
func runTickLoop(stock Stock, producer *kafka.Writer) {
    price := stock.BasePrice
    ticker := time.NewTicker(1 * time.Second)
    for range ticker.C {
        price = nextPrice(price)         // 랜덤워크
        tick := Tick{
            StockID: stock.ID, Symbol: stock.Symbol, Market: stock.Market,
            Price: price, Volume: randVolume(), TradeTime: time.Now(),
        }
        payload, _ := json.Marshal(tick)
        producer.WriteMessages(context.Background(), kafka.Message{
            Key:   []byte(strconv.FormatInt(stock.ID, 10)),  // 종목 ID를 파티션 키로
            Value: payload,
        })
    }
}
```

**파티션 키로 `stockId`를 쓰는 이유**: Kafka는 같은 키를 가진 메시지를 항상 같은 파티션에, 그리고 파티션 내에서는 발행 순서대로 보낸다. 종목별 가격 업데이트가 역전된 순서로 소비되면(예: 09:00:02 틱이 09:00:01 틱보다 먼저 처리) 캔들 집계가 깨지므로, 종목 단위 순서 보장이 필수다.

### 2.3 데이터 소스 확장 지점

현재는 랜덤워크로 Mock 틱을 생성하지만, 구조상 `runTickLoop` 내부를 실제 KIS WebSocket 구독으로 교체하면 그대로 실거래소 데이터 수집기가 된다 — Go가 WebSocket 클라이언트를 다루는 코드(`gorilla/websocket` 등)는 익숙한 패턴이다. 이번 변경 범위에는 포함하지 않았다(ADR-005 참고).

---

## 3. Kafka 토픽 설계

| 토픽 | 키 | 값 | 파티션 수 | 용도 |
|------|-----|-----|----------|------|
| `market.ticks` | stockId | 틱 JSON | 6 | 원시 시세 |
| `market.events` | stockId | 이벤트 JSON | 3 | 탐지된 이상 이벤트(가격급등/거래량급증) |

```json
// market.ticks 메시지 예시
{ "stockId": 2, "symbol": "005930", "market": "KOSPI", "price": 71200, "volume": 1839200, "tradeTime": "2026-07-01T09:30:05Z" }

// market.events 메시지 예시
{ "stockId": 2, "eventType": "VOLUME_SURGE", "title": "거래량 급증", "importanceScore": 87, "eventTime": "2026-07-01T09:30:05Z" }
```

파티션 수를 토픽마다 다르게 둔 이유는 소비자 측 병렬도 요구가 다르기 때문이다 — 틱은 캔들 집계·브로드캐스트 양쪽에서 고빈도로 소비되므로 더 잘게 쪼갰고, 이벤트는 상대적으로 저빈도다.

---

## 4. Kotlin Worker — Kafka Consumer

```kotlin
@Component
class TickKafkaConsumer(
    private val redisTickWriter: RedisTickWriter,
    private val candleAggregator: CandleAggregator,
    private val eventDetector: EventDetector,
    private val eventKafkaProducer: EventKafkaProducer,
) {
    @KafkaListener(topics = ["market.ticks"], groupId = "monticker-worker")
    fun onTick(record: ConsumerRecord<String, String>) {
        val tick = objectMapper.readValue(record.value(), GeneratedTick::class.java)
        redisTickWriter.write(tick)
        candleAggregator.onTick(tick)
        eventDetector.detect(tick)?.let { eventKafkaProducer.send(it) }
    }
}
```

기존 `MarketDataCollector.collect()`(1초 주기 폴링)가 하던 일을 Kafka 메시지 도착 시점에 반응하는 방식으로 바꿨다 — **폴링에서 푸시 기반으로 전환**된 것이 핵심 차이다. `groupId`를 지정해 컨슈머 그룹으로 등록하면, 향후 Worker를 수평 확장할 때 파티션이 자동으로 인스턴스 간에 분배된다(컨슈머 그룹의 표준 동작).

---

## 5. Netty Broadcast Gateway — 왜 Spring STOMP를 우회하는가

### 5.1 기존 경로의 문제

`backend/api`의 `PriceBroadcaster`는 Spring `SimpMessagingTemplate.convertAndSend()`를 통해 STOMP 메시지를 브로드캐스트하도록 작성돼 있었지만, 실제로는 **어떤 스케줄러도 호출하지 않아 죽은 코드**였다(ADR-005 참고). 설계 자체도 매 메시지마다 Spring MVC 메시지 디스패치 레이어를 거치므로, 초당 수천 건의 가격 업데이트를 푸시해야 하는 핫패스에는 오버헤드가 크다.

### 5.2 Netty 구현

```kotlin
// services/broadcast-gateway/src/main/kotlin/BroadcastServer.kt
class BroadcastServer(private val port: Int) {
    private val channels = ConcurrentHashMap<Long, MutableSet<Channel>>()  // stockId → 구독 채널

    fun start() {
        val bossGroup = NioEventLoopGroup(1)
        val workerGroup = NioEventLoopGroup()
        ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpServerCodec())
                        .addLast(HttpObjectAggregator(65536))
                        .addLast(WebSocketServerProtocolHandler("/ws"))
                        .addLast(SubscriptionHandler(channels))   // 클라이언트 구독 메시지 처리
                }
            })
            .bind(port).sync()
    }

    fun broadcastTick(stockId: Long, json: String) {
        channels[stockId]?.forEach { it.writeAndFlush(TextWebSocketFrame(json)) }
    }
}
```

```kotlin
// Kafka consumer thread → Netty broadcast
class TickConsumerLoop(private val server: BroadcastServer) {
    fun run() {
        val consumer = KafkaConsumer<String, String>(consumerProps)
        consumer.subscribe(listOf("market.ticks", "market.events"))
        while (true) {
            val records = consumer.poll(Duration.ofMillis(100))
            for (r in records) server.broadcastTick(r.key().toLong(), r.value())
        }
    }
}
```

Netty의 `EventLoopGroup`은 적은 수의 스레드로 수많은 채널의 I/O를 논블로킹으로 처리한다(리액터 패턴). Spring MVC의 "요청당 스레드" 모델과 달리, 연결된 WebSocket 클라이언트 수가 늘어나도 스레드 수는 거의 늘지 않는다 — 이는 정확히 시세 브로드캐스트처럼 "많은 연결에 같은 데이터를 자주 밀어줘야 하는" 워크로드에 맞는 모델이다.

### 5.3 구독 관리

클라이언트가 WebSocket 연결 후 `{"action":"subscribe","stockId":2}` 메시지를 보내면 `SubscriptionHandler`가 해당 채널을 `channels[2]`에 등록한다. 종목별로 구독자를 나눠 관리하므로, 특정 종목에 관심 있는 클라이언트에게만 데이터를 보내고 불필요한 트래픽을 줄인다.

---

## 6. 기존 경로와의 공존

```yaml
# backend/worker/application.yml
ingestion:
  source: internal   # internal(기존 MockPriceGenerator) | kafka(Go 게이트웨이 경유)
```

`ingestion.source=kafka`가 아니면 `MarketDataCollector`는 기존처럼 동작한다. Kafka·Go·Netty 게이트웨이는 모두 `docker-compose.yml`의 `kafka` profile로 묶여 있어, `docker compose --profile kafka up`을 명시적으로 실행해야만 뜬다 — 평소 `dev.sh` 흐름에는 영향을 주지 않는다.

---

## 7. 한계와 트레이드오프

- **스키마 검증 없음**: `market.ticks`/`market.events`는 평문 JSON이다. Go·Kotlin 양쪽이 필드 이름·타입을 합의해야 하며, 둘 중 하나가 깨지면 런타임에야 발견된다. 실서비스라면 Avro + Schema Registry로 컴파일 타임 검증을 추가해야 한다.
- **Exactly-once 보장 없음**: 현재 컨슈머는 `at-least-once`로 동작한다(커밋 후 처리 실패 시 중복 가능). 캔들 집계는 멱등(`ON CONFLICT DO UPDATE`)하므로 중복에 강하지만, 다른 컨슈머를 추가할 때는 멱등성을 직접 보장해야 한다.
- **단일 Kafka 브로커**: KRaft 모드 단일 노드로 구성했다. 운영 환경이라면 최소 3대 복제가 필요하다.
- **Go 게이트웨이의 종목 목록은 시작 시 1회 로드**: 종목이 추가되면 게이트웨이를 재시작해야 한다 — DB 폴링이나 별도 종목 추가 토픽이 필요하다.
