package com.monticker.api.quant.application

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.events.OrderFilledEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component

/**
 * trading-service가 발행하는 trading.order-filled 토픽을 소비한다.
 *
 * MSA 모드에서 OrderFilledStrategyListener(Spring Event 방식)를 대체한다.
 * quant.trading-events.enabled=true 일 때만 활성화 (MSA 모드 구분).
 */
@Component
@ConditionalOnProperty(name = ["quant.trading-events.enabled"], havingValue = "true")
class OrderFilledKafkaConsumer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @RetryableTopic(
        attempts = "4",
        backoff = Backoff(delay = 3_000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "false",
    )
    @KafkaListener(topics = ["trading.order-filled"], groupId = "monticker-quant-engine")
    fun onOrderFilled(record: ConsumerRecord<String, String>) {
        val event = try {
            objectMapper.readValue(record.value(), OrderFilledEvent::class.java)
        } catch (ex: JsonProcessingException) {
            // 잘못된 JSON, 누락된 필수 필드 등 역직렬화 실패는 재시도해도 성공할 수 없는
            // 영구적 오류다 (RetryableTopic이 재시도/DLT로 보내면 낭비이므로) — 삼키고 로그만 남긴다.
            log.error(
                "[Quant] OrderFilled 메시지 역직렬화 실패 — 삼키고 무시. topic={} partition={} offset={} key={} payload={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value(), ex,
            )
            return
        }
        log.info("[Quant] OrderFilled (Kafka): userId={} stockId={} side={} qty={} price={}",
            event.userId, event.stockId, event.side, event.quantity, event.fillPrice)
        // 향후 RuleSet 실전 검증(live tracking) 로직 추가 지점
    }

    @DltHandler
    fun onOrderFilledDlt(record: ConsumerRecord<String, String>) {
        log.error(
            "[DLT] trading.order-filled 최종 실패 — 포트폴리오 추적 누락, 수동 검토 필요. " +
            "topic={} partition={} offset={} key={}",
            record.topic(), record.partition(), record.offset(), record.key(),
        )
    }
}
