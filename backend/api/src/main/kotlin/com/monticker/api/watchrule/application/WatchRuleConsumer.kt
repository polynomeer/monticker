package com.monticker.api.watchrule.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.watchrule.events.StockEventDetectedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component

/**
 * ADR-051 — worker가 아웃박스로 발행한 탐지 이벤트를 받아 watch rule을 돌린다.
 *
 * 컨슈머 **그룹을 쓴다**(브로드캐스트인 ADR-038의 틱 컨슈머와 반대다). 주문은 작업이지 알림이 아니라서,
 * api 인스턴스가 여럿이면 각 이벤트를 하나만 처리해야 한다. 리밸런싱 중 중복 소비가 일어나도
 * [WatchRuleExecutor]의 멱등 키가 중복 체결을 막는다.
 *
 * 재시도 후에도 실패하면 DLT로 보낸다. 그 이벤트의 룰은 발동하지 않은 채 남으며, 자동 재처리하지
 * 않는다 — 돈이 움직이는 경로에서 "한참 뒤에 자동으로 체결됨"은 사용자가 예상할 수 없는 동작이다.
 * 원장 불일치와 같은 원칙이다(runbooks/ledger-mismatch.md): 사람이 보고 결정한다.
 */
@Component
class WatchRuleConsumer(
    private val executor: WatchRuleExecutor,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dlt = registry.counter("dlt_messages_total", "topic", TOPIC)

    @RetryableTopic(
        attempts = "3",
        backoff = Backoff(delay = 2_000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "false",
    )
    @KafkaListener(topics = [TOPIC], groupId = GROUP)
    fun onEvent(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue(record.value(), StockEventDetectedEvent::class.java)
        executor.onEvent(event)
    }

    @DltHandler
    fun onDlt(record: ConsumerRecord<String, String>) {
        dlt.increment()
        log.error("[WatchRule] DLT — 이 이벤트의 룰 중 일부가 재시도 끝에 실패했다(나머지는 처리됐을 수 있다 — watch_rule_executions 확인). 수동 검토 필요: {}", record.value())
    }

    companion object {
        const val TOPIC = "market.event-detected"
        const val GROUP = "monticker-watch-rule"
        /** KafkaTopicConfig.RetryFamilies 와 동기화할 것 — 어노테이션 attempts 와 이중 관리다. */
        const val ATTEMPTS = 3
    }
}
