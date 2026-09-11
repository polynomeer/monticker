package com.monticker.worker.alert

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

const val ALERT_RULES_CHANGED_CHANNEL = "alert:rules:changed"

/** ADR-044 — 인덱스·캐시·싱크 빈 조립. dispatch-mode는 kafka(기본) | inline. */
@Configuration
class AlertRuleIndexConfig {

    @Bean fun alertRuleIndex(jdbc: JdbcTemplate, meterRegistry: MeterRegistry) = AlertRuleIndex(jdbc, meterRegistry)
    @Bean fun indicatorCache(jdbc: JdbcTemplate) = IndicatorCache(jdbc)

    @Bean
    fun alertTriggerSink(
        @Value("\${alert.dispatch-mode:kafka}") mode: String,
        dispatcher: AlertDispatcher,
        kafkaSink: org.springframework.beans.factory.ObjectProvider<com.monticker.worker.kafka.NotifyKafkaProducer>,
    ): AlertTriggerSink =
        if (mode == "inline") InlineTriggerSink(dispatcher)
        else kafkaSink.getIfAvailable()?.let { producer -> AlertTriggerSink { rule, price -> producer.publish(rule, price) } }
            ?: InlineTriggerSink(dispatcher)

    /** api의 AlertService가 커밋 후 발행하는 stockId를 받아 그 종목만 다시 읽는다. */
    @Bean
    fun alertRulesChangedListener(factory: RedisConnectionFactory, index: AlertRuleIndex): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            addMessageListener({ message, _ ->
                val stockId = String(message.body).trim().toLongOrNull() ?: return@addMessageListener
                runCatching { index.reload(stockId) }
                    .onFailure { LoggerFactory.getLogger(AlertRuleIndexConfig::class.java).warn("[AlertRuleIndex] 재로드 실패 stockId={}: {}", stockId, it.message) }
            }, ChannelTopic(ALERT_RULES_CHANGED_CHANNEL))
        }
}

/** 기동 시 전체 로드(비동기 — 로드 중엔 DB 폴백으로 평가) + 5분 주기 보정. */
@Component
class AlertRuleIndexLoader(private val index: AlertRuleIndex) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Async
    fun loadOnStartup() {
        runCatching { index.loadAll() }.onFailure { log.error("[AlertRuleIndex] 초기 로드 실패 — DB 폴백으로 계속: {}", it.message) }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    fun sync() {
        runCatching { index.syncDelta() }.onFailure { log.warn("[AlertRuleIndex] 보정 재로드 실패: {}", it.message) }
    }
}
