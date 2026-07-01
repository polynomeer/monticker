# Netty Broadcast Gateway — 설계 및 구현

> 관련: [ADR-005](../decisions/005-kafka-go-gateway-netty-broadcast.md), [kafka-tick-pipeline.md](./kafka-tick-pipeline.md)

## 1. 역할

`services/broadcast-gateway` 는 Kafka `market.ticks`·`market.events` 토픽을 소비하고, WebSocket으로 연결된 클라이언트에게 실시간으로 시세 데이터를 밀어낸다. Spring STOMP 스택을 거치지 않고 Netty 위에 직접 구축해 스레드 수를 최소화한다.

---

## 2. 왜 Netty인가

### Spring STOMP 대비

| 항목 | Spring STOMP (기존) | Netty Broadcast Gateway |
|------|---------------------|------------------------|
| 스레드 모델 | 연결당 1개 이상 (기본 8개 + Tomcat NIO) | boss 1 + worker N (기본 CPU×2) |
| 브로드캐스트 방식 | `SimpMessagingTemplate.convertAndSend()` | `channel.writeAndFlush(TextWebSocketFrame)` |
| 프로토콜 오버헤드 | STOMP 헤더 + SockJS envelope | Raw WebSocket 프레임 |
| 구독 관리 | Spring 내부 구독 레지스트리 | `ConcurrentHashMap<String, Set<Channel>>` |
| Kafka 연동 | 별도 `@KafkaListener` Bean 필요 | KafkaBridge 스레드가 직접 호출 |

시세 브로드캐스트는 "동일 메시지를 수천 연결에 반복 전달" 하는 워크로드다. Netty의 NIO EventLoopGroup은 이 패턴에서 Spring STOMP보다 스레드를 10× 이상 적게 쓴다.

---

## 3. 아키텍처

```
Kafka consumers (KafkaBridge 스레드)
   │
   │ poll() every 200ms
   ▼
BroadcastServer.broadcast(stockId, json)
   │
   ├── subscriptions["<stockId>"] 조회 → 해당 채널 집합에 writeAndFlush
   └── subscriptions["ALL"] 조회 → 전체 구독자에게 writeAndFlush

클라이언트 연결 (SubscriptionHandler)
   connect → WS handshake (HTTP 101)
   {"action":"subscribe","stockId":"42"} → subscriptions["42"].add(channel)
   {"action":"unsubscribe","stockId":"42"} → subscriptions["42"].remove(channel)
   disconnect → subscriptions 전체에서 channel 제거
```

---

## 4. 핵심 클래스

### BroadcastServer

```kotlin
class BroadcastServer(private val port: Int) {
    // stockId → 구독 채널 집합. "ALL" 키는 필터 없이 전체 구독하는 클라이언트
    private val subscriptions = ConcurrentHashMap<String, MutableSet<Channel>>()

    fun start() {
        val bossGroup  = NioEventLoopGroup(1)      // accept 전담 스레드 1개
        val workerGroup = NioEventLoopGroup()       // I/O 처리 스레드 (기본 CPU×2)

        ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpServerCodec())           // HTTP 요청 파싱
                        .addLast(HttpObjectAggregator(65536)) // HTTP 프레임 집약
                        .addLast(WebSocketServerProtocolHandler("/ws")) // WS 핸드셰이크
                        .addLast(SubscriptionHandler(subscriptions))    // 구독 처리
                }
            })
            .bind(port).sync().channel()
            .closeFuture().sync()
    }

    fun broadcast(stockId: String, json: String) {
        val makeFrame = { TextWebSocketFrame(json) }
        subscriptions[stockId]?.forEach { it.writeAndFlush(makeFrame()) }
        subscriptions["ALL"]?.forEach  { it.writeAndFlush(makeFrame()) }
    }
}
```

**`TextWebSocketFrame` 을 채널마다 새로 생성하는 이유**: Netty의 reference-counted 버퍼는 채널별 독립 소유권을 요구한다. 같은 인스턴스를 여러 채널에 보내면 double-release 오류가 발생한다.

### SubscriptionHandler

```kotlin
class SubscriptionHandler(
    private val subscriptions: ConcurrentHashMap<String, MutableSet<Channel>>,
) : SimpleChannelInboundHandler<TextWebSocketFrame>() {

    override fun channelRead0(ctx: ChannelHandlerContext, msg: TextWebSocketFrame) {
        val node   = mapper.readTree(msg.text())
        val action = node["action"]?.asText() ?: return
        val key    = node["stockId"]?.asText() ?: "ALL"

        when (action) {
            "subscribe"   -> subscriptions.getOrPut(key) { ConcurrentHashMap.newKeySet() }
                                          .add(ctx.channel())
            "unsubscribe" -> subscriptions[key]?.remove(ctx.channel())
            else -> log.debug("알 수 없는 action: {}", action)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // 연결 끊김 시 모든 구독 목록에서 채널 제거 (메모리 누수 방지)
        subscriptions.values.forEach { it.remove(ctx.channel()) }
        super.channelInactive(ctx)
    }
}
```

`ConcurrentHashMap.newKeySet()` — `ConcurrentHashSet` 이 없기 때문에 이 패턴을 사용한다. 스레드 안전한 HashSet.

### KafkaBridge

```kotlin
class KafkaBridge(private val brokers: String, private val server: BroadcastServer) {
    private val running = AtomicBoolean(true)

    fun run() {
        val consumer = KafkaConsumer<String, String>(Properties().apply {
            put(BOOTSTRAP_SERVERS_CONFIG, brokers)
            put(GROUP_ID_CONFIG, "broadcast-gateway")
            put(KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer::class.java.name)
            put(VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(AUTO_OFFSET_RESET_CONFIG, "latest")  // 과거 메시지는 스킵
        })
        consumer.subscribe(listOf("market.ticks", "market.events"))

        while (running.get()) {
            val records = consumer.poll(Duration.ofMillis(200))
            for (record in records) {
                // record.key() = stockId (Go gateway가 키로 설정한 값)
                server.broadcast(record.key() ?: "ALL", record.value())
            }
        }
        consumer.close()
    }
}
```

`AUTO_OFFSET_RESET_CONFIG = latest` — 브로드캐스트 게이트웨이는 실시간 중계가 목적이므로 재시작 후 과거 틱을 재처리할 이유가 없다.

---

## 5. 클라이언트 프로토콜

```
# 1. WebSocket 연결
ws://localhost:9090/ws

# 2. 특정 종목 구독
→ {"action": "subscribe", "stockId": "42"}

# 3. 전체 종목 구독
→ {"action": "subscribe", "stockId": "ALL"}

# 4. 구독 해제
→ {"action": "unsubscribe", "stockId": "42"}

# 5. 서버 → 클라이언트 메시지 (Kafka에서 받은 JSON 그대로)
← {"stockId":42,"symbol":"005930","market":"KOSPI","price":72500.0,"volume":1234,...}
```

---

## 6. 진입점 (`Main.kt`)

```kotlin
fun main() {
    val port    = System.getenv("BROADCAST_PORT")?.toInt() ?: 9090
    val brokers = System.getenv("KAFKA_BROKERS") ?: "localhost:9092"

    val server = BroadcastServer(port)
    val bridge = KafkaBridge(brokers, server)

    // KafkaBridge는 블로킹 루프이므로 별도 스레드에서 실행
    thread(name = "kafka-bridge") { bridge.run() }

    // BroadcastServer.start()는 closeFuture().sync()로 블로킹
    server.start()
}
```

---

## 7. 빌드 및 배포

```bash
# 로컬 빌드 (shadow jar)
./gradlew shadowJar
java -jar build/libs/broadcast-gateway-0.0.1-SNAPSHOT-all.jar

# Docker
docker build -t monticker/broadcast-gateway .

# Docker Compose (kafka 프로필)
docker compose --profile kafka up broadcast-gateway
```

`com.github.johnrengelman.shadow` 플러그인으로 의존성 포함 fat-jar 생성. Kotlin stdlib + Netty + Kafka client 합산 ~25MB.

---

## 8. 의존성

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.netty:netty-all:4.1.111.Final")
    implementation("org.apache.kafka:kafka-clients:3.8.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}
```

Spring 없이 Netty + kafka-clients + Jackson만 사용. JVM 시작 시간 ~800ms (Spring Boot 대비 약 5× 빠름).

---

## 9. 알려진 한계

| 항목 | 현황 | 향후 개선 |
|------|------|----------|
| TLS/WSS | 미적용, ws:// 평문 | Let's Encrypt + SslContext 추가 |
| 인증 | WS 핸드셰이크 시 토큰 검증 없음 | JWT 쿼리스트링 검증 핸들러 추가 |
| 연결 수 제한 | 무제한 | `maxConnections` 설정 + 초과 시 4008 close |
| 모니터링 | 없음 | Prometheus HTTP 엔드포인트 추가 |
| 단일 브로커 | `kafka-clients` 기반 단순 컨슈머 | NATS JetStream으로 교체 시 더 낮은 지연 가능 |
