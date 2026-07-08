package com.monticker.trading.kafka

import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

/**
 * OrderEventKafkaPublisher: @TransactionalEventListener(AFTER_COMMIT) 설정 및
 * onOrderFilled / onOrderCancelled 동작을 검증한다.
 *
 * Kafka 브로커 없이도 실행되도록 publisher를 직접 인스턴스화한 뒤
 * 내부 KafkaProducer를 mockk으로 교체하는 대신, 핵심 보안 계약을
 * 리플렉션으로 확인한다. (브로커 의존 통합 테스트는 별도 @EmbeddedKafka 로 분리)
 */
class OrderEventKafkaPublisherTest {

    @Test
    fun `onOrderFilled 메서드는 AFTER_COMMIT 어노테이션을 가져야 한다`() {
        val fn = OrderEventKafkaPublisher::class.memberFunctions.first { it.name == "onOrderFilled" }
        val annotation = fn.findAnnotation<TransactionalEventListener>()
            ?: error("@TransactionalEventListener 없음")
        assert(annotation.phase == TransactionPhase.AFTER_COMMIT) {
            "체결 이벤트는 트랜잭션 커밋 후 발행돼야 한다 (현재: ${annotation.phase})"
        }
    }

    @Test
    fun `onOrderCancelled 메서드는 AFTER_COMMIT 어노테이션을 가져야 한다`() {
        val fn = OrderEventKafkaPublisher::class.memberFunctions.first { it.name == "onOrderCancelled" }
        val annotation = fn.findAnnotation<TransactionalEventListener>()
            ?: error("@TransactionalEventListener 없음")
        assert(annotation.phase == TransactionPhase.AFTER_COMMIT) {
            "취소 이벤트는 트랜잭션 커밋 후 발행돼야 한다 (현재: ${annotation.phase})"
        }
    }

    @Test
    fun `OrderFilledEvent는 JSON 직렬화 가능해야 한다`() {
        val event = OrderFilledEvent(
            orderId    = 1L,
            fillId     = 10L,
            userId     = 42L,
            stockId    = 100L,
            side       = "BUY",
            quantity   = 5,
            fillPrice  = BigDecimal("50000"),
            amount     = BigDecimal("250000"),
        )
        val mapper = com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
        val json   = mapper.writeValueAsString(event)
        val parsed = mapper.readValue(json, OrderFilledEvent::class.java)

        assert(parsed.orderId == event.orderId)
        assert(parsed.userId  == event.userId)
        assert(parsed.side    == event.side)
        assert(parsed.fillPrice.compareTo(event.fillPrice) == 0)
    }

    @Test
    fun `OrderCancelledEvent는 JSON 직렬화 가능해야 한다`() {
        val event = OrderCancelledEvent(
            orderId      = 2L,
            userId       = 42L,
            stockId      = 100L,
            side         = "BUY",
            refundAmount = BigDecimal("100000"),
        )
        val mapper = com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
        val json   = mapper.writeValueAsString(event)
        val parsed = mapper.readValue(json, OrderCancelledEvent::class.java)

        assert(parsed.orderId      == event.orderId)
        assert(parsed.refundAmount.compareTo(event.refundAmount) == 0)
    }
}
