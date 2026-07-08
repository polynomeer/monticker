package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.events.OrderFilledEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.math.BigDecimal
import java.time.Instant

/**
 * OrderFilledKafkaConsumer: Kafka 메시지 파싱 및 예외 내성 검증.
 *
 * 브로커 없이 onOrderFilled()를 직접 호출한다.
 * 실제 Kafka 연동은 @EmbeddedKafka 통합 테스트로 별도 분리.
 */
class OrderFilledKafkaConsumerTest {

    private val consumer = OrderFilledKafkaConsumer()
    private val mapper   = ObjectMapper().findAndRegisterModules()

    private fun record(payload: String) =
        ConsumerRecord<String, String>("trading.order-filled", 0, 0L, "key", payload)

    private fun eventJson(
        orderId: Long = 1L,
        userId: Long = 42L,
        stockId: Long = 100L,
        side: String = "BUY",
        quantity: Int = 10,
        fillPrice: BigDecimal = BigDecimal("50000"),
        amount: BigDecimal = BigDecimal("500000"),
    ) = mapper.writeValueAsString(
        OrderFilledEvent(
            orderId   = orderId,
            fillId    = orderId * 10,
            userId    = userId,
            stockId   = stockId,
            side      = side,
            quantity  = quantity,
            fillPrice = fillPrice,
            amount    = amount,
            filledAt  = Instant.parse("2024-01-15T09:00:00Z"),
        )
    )

    @Test
    fun `올바른 JSON 페이로드를 예외 없이 처리한다`() {
        assertDoesNotThrow {
            consumer.onOrderFilled(record(eventJson()))
        }
    }

    @Test
    fun `BUY 이벤트를 정상 처리한다`() {
        assertDoesNotThrow {
            consumer.onOrderFilled(record(eventJson(side = "BUY", quantity = 5)))
        }
    }

    @Test
    fun `SELL 이벤트를 정상 처리한다`() {
        assertDoesNotThrow {
            consumer.onOrderFilled(record(eventJson(side = "SELL", quantity = 3)))
        }
    }

    @Test
    fun `잘못된 JSON이 들어와도 예외를 삼키고 로그만 남긴다`() {
        // Kafka consumer는 파싱 실패 시 크래시하면 안 된다
        assertDoesNotThrow {
            consumer.onOrderFilled(record("{ invalid json }"))
        }
    }

    @Test
    fun `빈 payload도 예외를 삼키고 로그만 남긴다`() {
        assertDoesNotThrow {
            consumer.onOrderFilled(record(""))
        }
    }

    @Test
    fun `OrderFilledEvent 필수 필드가 누락된 JSON도 예외를 삼킨다`() {
        assertDoesNotThrow {
            consumer.onOrderFilled(record("""{"orderId":1}"""))
        }
    }
}
