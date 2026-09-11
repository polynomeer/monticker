package com.monticker.api.marketdata.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceTick
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.TopicPartitionOffset
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ADR-038 — market.ticks를 **컨슈머 그룹 없이 전 파티션 수동 할당**으로 구독한다.
 *
 * 이전(ADR-029)에는 @KafkaListener(groupId="monticker-api-broadcast")였다. 같은 그룹의 api 인스턴스끼리
 * 파티션을 나눠 갖으므로, replicas ≥ 2에서 pod B에 붙은 클라이언트는 pod A가 소비한 파티션의 틱을
 * 영원히 받지 못했다 — SimpleBroker가 pod 로컬이기 때문이다. prod 매니페스트는 이미 replicas 2였다.
 *
 * 브로드캐스트에는 컨슈머 그룹이 필요 없다: 모든 인스턴스가 모든 것을 원한다. 기동 시 브로커에서
 * 파티션 수를 읽어 전부 할당하고 각 파티션의 끝(END)으로 seek한다. 오프셋은 커밋하지 않는다.
 * 고유 group.id 방식보다 나은 이유: pod 재시작마다 그룹 메타데이터가 쌓이지 않고 리밸런스가 없다.
 *
 * Kafka가 아직 없거나 토픽이 아직 없으면(로컬 개발, 첫 기동) 앱 부팅을 막지 않고 백그라운드에서
 * 10초마다 재시도한다 — 이전 @KafkaListener도 같은 동작이었다. 파티션이 늘면(ADR-040) 재기동해야
 * 새 파티션을 잡는다(할당은 기동 시 1회).
 */
@Component
class MarketTickBroadcastConsumer(
    private val priceBroadcaster: PriceBroadcaster,
    private val eventPublisher: ApplicationEventPublisher,
    private val consumerFactory: ConsumerFactory<String, String>,
    private val kafkaAdmin: KafkaAdmin,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val failed = meterRegistry.counter("tick_broadcast_failed_total")
    private val running = AtomicBoolean(false)
    @Volatile private var container: ConcurrentMessageListenerContainer<String, String>? = null

    companion object {
        const val TOPIC = "market.ticks"
        const val RETRY_SECONDS = 10L

        /** 파티션 0..n-1 전부를 END에서부터 — 순수 함수라 테스트로 고정한다. */
        fun assignmentsFor(partitionCount: Int): Array<TopicPartitionOffset> =
            Array(partitionCount) { TopicPartitionOffset(TOPIC, it, TopicPartitionOffset.SeekPosition.END) }
    }

    fun onTick(record: ConsumerRecord<String, String>) {
        runCatching {
            val wire = objectMapper.readValue(record.value(), MarketTickMessage::class.java)
            val tick = PriceTick(
                stockId   = wire.stockId,
                symbol    = wire.symbol,
                price     = wire.price,
                volume    = wire.volume,
                tradeTime = wire.tradeTime,
            )
            priceBroadcaster.broadcast(tick)
            // ADR-032 — ConditionalOrderEvaluator가 구독한다. 조건부 주문 평가는 conflation하지 않는다 —
            // 모든 틱을 봐야 한다. 리스너가 @Async라 이 컨슈머 스레드를 블로킹하지 않는다.
            eventPublisher.publishEvent(MarketTickReceivedEvent(tick))
        }.onFailure { e ->
            // 브로드캐스트는 유실 허용 경로다(다음 틱이 곧 온다) — 삼키되 카운터로 남긴다.
            failed.increment()
            log.warn("[MarketTickBroadcast] 틱 처리 실패: {}", e.message)
        }
    }

    // ── SmartLifecycle ──────────────────────────────────────────────────────

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "tick-broadcast-bootstrap", isDaemon = true) {
            while (running.get() && container == null) {
                try {
                    val partitions = partitionCount()
                    val props = ContainerProperties(*assignmentsFor(partitions)).apply {
                        messageListener = MessageListener<String, String> { onTick(it) }
                        ackMode = ContainerProperties.AckMode.MANUAL     // 커밋하지 않는다
                        setMissingTopicsFatal(false)
                    }
                    val c = ConcurrentMessageListenerContainer(consumerFactory, props)
                    c.setBeanName("marketTickBroadcastContainer")
                    c.start()
                    container = c
                    log.info("[MarketTickBroadcast] {} 파티션 {}개 전부 할당(END부터), 컨슈머 그룹 없음", TOPIC, partitions)
                } catch (e: Exception) {
                    log.warn("[MarketTickBroadcast] Kafka 연결/토픽 조회 실패 — {}초 후 재시도: {}", RETRY_SECONDS, e.message)
                    try { TimeUnit.SECONDS.sleep(RETRY_SECONDS) } catch (_: InterruptedException) { return@thread }
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
        container?.stop()
        container = null
    }

    override fun isRunning(): Boolean = running.get()
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = Int.MAX_VALUE - 100   // 웹 서버 뒤, 대부분의 빈 뒤에 시작

    private fun partitionCount(): Int =
        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            val desc = admin.describeTopics(listOf(TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS)[TOPIC]
                ?: throw IllegalStateException("토픽 $TOPIC 없음")
            desc.partitions().size
        }

    // backend/worker의 GeneratedTick(및 Go market-gateway의 Tick)과 필드명을 맞춰야 한다 —
    // 스키마 레지스트리 없이 관례로만 동기화된다(ADR-005 참고, ADR-040 Revisit).
    private data class MarketTickMessage(
        val stockId: Long,
        val symbol: String,
        val market: String,
        val price: BigDecimal,
        val volume: Long,
        val tradeTime: Instant,
        val generatedAt: Instant = Instant.now(),
        val marketStatus: String = "OPEN",
    )
}
