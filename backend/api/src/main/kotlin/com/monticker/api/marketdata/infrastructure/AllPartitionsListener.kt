package com.monticker.api.marketdata.infrastructure

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.TopicPartitionOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ADR-038 — 컨슈머 그룹 없이 토픽의 **전 파티션을 이 인스턴스에 할당**하는 리스너.
 *
 * 브로드캐스트 성격의 토픽(시세, 시장 요약)은 모든 api 인스턴스가 모든 메시지를 원한다.
 * 컨슈머 그룹은 파티션을 인스턴스끼리 나누므로 부적합하다 — SimpleBroker가 pod 로컬이라
 * 다른 pod가 받은 메시지는 이 pod의 세션에 절대 오지 않는다.
 *
 * 기동 시 브로커에서 파티션 수를 읽어 전부 END부터 구독하고 커밋하지 않는다. Kafka/토픽이 아직
 * 없으면 앱 부팅을 막지 않고 백그라운드에서 재시도한다. 파티션이 늘면 재기동해야 새 파티션을 잡는다.
 */
class AllPartitionsListener(
    private val topic: String,
    private val consumerFactory: ConsumerFactory<String, String>,
    private val kafkaAdmin: KafkaAdmin,
    private val handler: (ConsumerRecord<String, String>) -> Unit,
    private val retrySeconds: Long = 10,
) {
    private val log = LoggerFactory.getLogger(AllPartitionsListener::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var container: ConcurrentMessageListenerContainer<String, String>? = null

    companion object {
        /** 파티션 0..n-1 전부를 END에서부터 — 순수 함수라 테스트로 고정한다. */
        fun assignmentsFor(topic: String, partitionCount: Int): Array<TopicPartitionOffset> =
            Array(partitionCount) { TopicPartitionOffset(topic, it, TopicPartitionOffset.SeekPosition.END) }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "all-partitions-$topic", isDaemon = true) {
            while (running.get() && container == null) {
                try {
                    val partitions = partitionCount()
                    val props = ContainerProperties(*assignmentsFor(topic, partitions)).apply {
                        messageListener = MessageListener<String, String> { handler(it) }
                        ackMode = ContainerProperties.AckMode.MANUAL     // 커밋하지 않는다
                        setMissingTopicsFatal(false)
                    }
                    val c = ConcurrentMessageListenerContainer(consumerFactory, props)
                    c.setBeanName("allPartitions-$topic")
                    c.start()
                    container = c
                    log.info("[AllPartitions] {} 파티션 {}개 전부 할당(END부터), 컨슈머 그룹 없음", topic, partitions)
                } catch (e: Exception) {
                    log.warn("[AllPartitions] {} 연결/조회 실패 — {}초 후 재시도: {}", topic, retrySeconds, e.message)
                    try { TimeUnit.SECONDS.sleep(retrySeconds) } catch (_: InterruptedException) { return@thread }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        container?.stop()
        container = null
    }

    fun isRunning(): Boolean = running.get()

    private fun partitionCount(): Int =
        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            val desc = admin.describeTopics(listOf(topic)).allTopicNames().get(5, TimeUnit.SECONDS)[topic]
                ?: throw IllegalStateException("토픽 $topic 없음")
            desc.partitions().size
        }
}
