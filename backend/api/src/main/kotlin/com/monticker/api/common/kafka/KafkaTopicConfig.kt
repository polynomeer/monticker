package com.monticker.api.common.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import java.time.Duration

/**
 * ADR-040 — Kafka 토픽을 코드로 선언한다. 브로커 auto-create는 끈다(docker-compose, 운영 브로커).
 *
 * auto-create에 의존하면 모든 토픽이 파티션 1개로 생기고(브로커 기본값) 파티션·복제·retention을
 * 아무도 소유하지 않는다. market.ticks 파티션 1개 = 컨슈머 병렬성 1 = L-03 기준선의 600 tick/s 상한.
 *
 * KafkaAdmin이 기동 시 여기 선언된 토픽을 만들고, 이미 있으면 파티션 수가 더 크면 늘린다(줄이지는 못한다).
 * 파티션 수·복제 계수는 환경별로 다르므로 프로퍼티(app.kafka.*)로 주입한다 — 로컬 docker compose up이
 * 256개 파티션 디렉터리를 만들 이유가 없다. 운영 값은 K8s ConfigMap/overlay에 있다.
 *
 * 선언 위치가 backend/api 하나인 이유: 토픽 소유자가 둘이면 어긋난다. 다른 서비스(worker 등)는 api가
 * 먼저 기동해 토픽을 만들어 두는 것을 전제한다 — 그 전까지 프로듀서는 재시도/큐잉한다.
 *
 * @RetryableTopic(autoCreateTopics = "false")인 컨슈머 3곳의 재시도/DLT 토픽도 여기서 만든다.
 * auto-create만 끄고 이걸 놓치면 ADR-006의 재시도 전략이 조용히 통째로 무력화된다.
 * attempts 값은 어노테이션과 이중 관리다 — RetryFamilies의 상수를 바꾸면 어노테이션도 바꿔야 한다.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicProperties::class)
class KafkaTopicConfig(private val props: KafkaTopicProperties) {

    /** @RetryableTopic 컨슈머와 짝을 이루는 재시도 토픽 패밀리. (토픽, attempts) — attempts-1 개의 -retry-N + -dlt */
    object RetryFamilies {
        const val MARKET_TICKS_ATTEMPTS = 3          // worker TickKafkaConsumer
        const val TICK_PROCESSED_ATTEMPTS = 3        // worker AlertKafkaConsumer
        const val ORDER_FILLED_ATTEMPTS = 4          // quant-engine OrderFilledKafkaConsumer
        const val NOTIFY_ATTEMPTS = 3                // worker NotifyKafkaConsumer (ADR-044)
        const val SEARCH_INDEX_ATTEMPTS = 1          // api SearchIndexConsumer (ADR-042) — 배치 리스너라 블로킹 재시도 + -dlt만

        val all: Map<String, Int> = mapOf(
            "market.ticks" to MARKET_TICKS_ATTEMPTS,
            "market.tick-processed" to TICK_PROCESSED_ATTEMPTS,
            "trading.order-filled" to ORDER_FILLED_ATTEMPTS,
            "notify.commands" to NOTIFY_ATTEMPTS,
            "search.index" to SEARCH_INDEX_ATTEMPTS,
        )

        /** Spring Kafka SUFFIX_WITH_INDEX_VALUE + dltTopicSuffix "-dlt" 규칙 그대로. */
        fun names(topic: String, attempts: Int): List<String> =
            (0 until attempts - 1).map { "$topic-retry-$it" } + "$topic-dlt"
    }

    private fun topic(name: String, partitions: Int, retention: Duration, minIsr: Int? = null): NewTopic =
        TopicBuilder.name(name)
            .partitions(partitions)
            .replicas(props.replicationFactor)
            .config(TopicConfig.RETENTION_MS_CONFIG, retention.toMillis().toString())
            .apply { if (minIsr != null) config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minIsr.toString()) }
            .build()

    @Bean fun marketTicksTopic()      = topic("market.ticks",          props.partitions.marketTicks,    Duration.ofHours(6))
    @Bean fun marketSummaryTopic()    = topic("market.summary",        1,                               Duration.ofHours(1))
    @Bean fun marketEventsTopic()     = topic("market.events",         props.partitions.marketEvents,   Duration.ofDays(7))
    @Bean fun tickProcessedTopic()    = topic("market.tick-processed", props.partitions.tickProcessed,  Duration.ofHours(1))
    // ADR-044 — 평가→발송 분리. 키 = ruleId. 발송 워커가 느려도 틱 파이프라인이 밀리지 않는다.
    @Bean fun notifyCommandsTopic()   = topic("notify.commands",       props.partitions.tickProcessed,  Duration.ofDays(1))
    // 유실 불가 — 복제 계수가 2 이상일 때만 minIsr가 의미 있다(단일 브로커에서 minIsr=2면 쓰기가 막힌다).
    @Bean fun orderFilledTopic()      = topic("trading.order-filled",   props.partitions.trading, Duration.ofDays(30), minIsrIfReplicated())
    // ADR-042 — ES 인덱싱 아웃박스. 키 = "{index}:{docId}" 라 같은 문서의 색인/삭제 순서가 보장된다. 7일 = 재색인 되감기 창.
    @Bean fun searchIndexTopic()      = topic("search.index",           props.partitions.searchIndex, Duration.ofDays(7))
    @Bean fun orderCancelledTopic()   = topic("trading.order-cancelled", props.partitions.trading, Duration.ofDays(30), minIsrIfReplicated())

    // KafkaAdmin은 NewTopic 빈과 NewTopics(묶음) 빈만 수집한다 — List<NewTopic>은 무시된다.
    @Bean
    fun retryTopics(): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(*declaredRetryTopics().toTypedArray())

    /** NewTopics.getNewTopics()가 package-private이라 테스트가 볼 수 있도록 따로 노출한다. */
    fun declaredRetryTopics(): List<NewTopic> =
        RetryFamilies.all.flatMap { (base, attempts) ->
            RetryFamilies.names(base, attempts).map { topic(it, props.partitions.retry, Duration.ofDays(30)) }
        }

    private fun minIsrIfReplicated(): Int? = if (props.replicationFactor >= 2) 2 else null
}

@ConfigurationProperties(prefix = "app.kafka")
data class KafkaTopicProperties(
    /** 브로커 수에 종속. 단일 브로커(로컬)는 1, 멀티 브로커 전환 시 3. */
    val replicationFactor: Int = 1,
    val partitions: Partitions = Partitions(),
) {
    data class Partitions(
        val marketTicks: Int = 12,
        val tickProcessed: Int = 6,
        val marketEvents: Int = 3,
        val trading: Int = 3,
        val searchIndex: Int = 3,
        val retry: Int = 1,
    )
}
