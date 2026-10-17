package com.monticker.api.common

import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.common.search.SearchIndexEvent
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceSource
import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.TickProvenance
import com.monticker.api.marketdata.infrastructure.MarketTickBroadcastConsumer
import com.monticker.api.marketdata.infrastructure.RedisPriceCache
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.support.TestJson
import com.monticker.api.watchrule.events.StockEventDetectedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * api가 프로세스 경계로 주고받는 JSON의 **지금 형식**을 고정한다(docs/dependency-upgrade-plan.md B4-3).
 *
 * - 아웃박스 이벤트(`@Externalized`): Modulith가 **Spring Boot가 구성한 ObjectMapper**로 직렬화해 `event_publication`에
 *   저장하고 Kafka로 외부화한다. 같은 문자열이 Kafka 메시지이자 재전송 때 다시 읽는 저장 행이다 — 업그레이드 직전에 쌓인
 *   미완료 행을 새 버전이 읽어야 한다. Boot 3.5의 매퍼는 `Instant`를 ISO 문자열로, null 필드도 쓴다.
 * - worker·Go가 쓰는 메시지를 api가 읽는 경로(틱 브로드캐스트, watch rule 이벤트, Redis 시세).
 *
 * Spring Boot 4(Jackson 3)로 올린 뒤에도(ADR-104 — `use-jackson2-defaults`) 이 문자열이 그대로여야 한다. 매퍼는 실제
 * application.yml로 구성한 Boot 매퍼다([TestJson.boot]). worker 쪽은 backend/worker의 같은 이름 테스트,
 * Go 게이트웨이는 services/market-gateway/internal/tick/tick_test.go가 같은 형식을 고정한다.
 */
class WireFormatGoldenTest {

    /** Unit을 돌려준다 — 표현식 본문 테스트가 값을 반환하면 JUnit이 테스트로 인식하지 않는다 */
    private fun withBootMapper(block: (JsonMapper) -> Any?) {
        block(TestJson.boot)
    }

    private val t = Instant.parse("2026-10-16T01:00:00.123456789Z")

    // ── 아웃박스 이벤트(Kafka 외부화 + event_publication 저장) ──────────────────

    private val filled = OrderFilledEvent(
        orderId = 1, userId = 7, stockId = 5930, fillId = 11, side = "BUY", quantity = 3,
        fillPrice = BigDecimal("71200.50"), amount = BigDecimal("213601.50"), filledAt = t, origin = "WATCH_RULE", originRef = 99,
    )
    private val filledJson =
        """{"orderId":1,"userId":7,"stockId":5930,"fillId":11,"side":"BUY","quantity":3,"fillPrice":71200.50,"amount":213601.50,""" +
            """"filledAt":"2026-10-16T01:00:00.123456789Z","origin":"WATCH_RULE","originRef":99}"""

    @Test
    fun `trading order-filled — 쓰고, 지금 형식과 origin 이전 형식을 읽는다`() = withBootMapper { m ->
        assertThat(m.writeValueAsString(filled)).isEqualTo(filledJson)
        assertThat(m.readValue(filledJson, OrderFilledEvent::class.java)).isEqualTo(filled)
        // ADR-085 이전에 저장된 아웃박스 행 — origin·originRef가 없다
        val beforeOrigin = filledJson.replace(""","origin":"WATCH_RULE","originRef":99""", "")
        assertThat(m.readValue(beforeOrigin, OrderFilledEvent::class.java)).isEqualTo(filled.copy(origin = null, originRef = null))
    }

    @Test
    fun `trading order-cancelled — 쓰고 읽는다`() = withBootMapper { m ->
        val event = OrderCancelledEvent(1, 7, 5930, "BUY", BigDecimal("213601.50"), t, "USER")
        val json = """{"orderId":1,"userId":7,"stockId":5930,"side":"BUY","refundAmount":213601.50,""" +
            """"cancelledAt":"2026-10-16T01:00:00.123456789Z","reason":"USER"}"""
        assertThat(m.writeValueAsString(event)).isEqualTo(json)
        assertThat(m.readValue(json, OrderCancelledEvent::class.java)).isEqualTo(event)
    }

    @Test
    fun `notify user — worker가 읽는 형식(backend worker WireFormatGoldenTest와 같은 문자열)`() = withBootMapper { m ->
        val command = UserNotificationCommand(7, NotificationCategory.CONDITIONAL_ORDER, "t", "b", "k", mapOf("conditionalOrderId" to 12))
        val json = """{"userId":7,"category":"CONDITIONAL_ORDER","title":"t","body":"b","dedupKey":"k","data":{"conditionalOrderId":12}}"""
        assertThat(m.writeValueAsString(command)).isEqualTo(json)
        assertThat(m.readValue(json, UserNotificationCommand::class.java)).isEqualTo(command)
    }

    @Test
    fun `search index — api·worker가 같은 형식으로 쓰고 api가 읽는다(DELETE는 payload null)`() = withBootMapper { m ->
        val index = SearchIndexEvent.index("stocks", "5930", mapOf("symbol" to "005930", "listedAt" to "2026-10-16"))
        val indexJson = """{"index":"stocks","docId":"5930","op":"INDEX","payload":{"symbol":"005930","listedAt":"2026-10-16"}}"""
        val deleteJson = """{"index":"stocks","docId":"5930","op":"DELETE","payload":null}"""
        assertThat(m.writeValueAsString(index)).isEqualTo(indexJson)
        assertThat(m.writeValueAsString(SearchIndexEvent.delete("stocks", "5930"))).isEqualTo(deleteJson)
        assertThat(m.readValue(indexJson, SearchIndexEvent::class.java)).isEqualTo(index)
        assertThat(m.readValue(deleteJson, SearchIndexEvent::class.java)).isEqualTo(SearchIndexEvent.delete("stocks", "5930"))
    }

    // ── worker가 쓰고 api가 읽는다 ─────────────────────────────────────────────

    @Test
    fun `market event-detected — worker 아웃박스 형식을 읽는다(시각은 epoch millis 숫자)`() = withBootMapper { m ->
        val json = """{"eventId":321,"stockId":5930,"eventType":"PRICE_SPIKE","importanceScore":80,"eventTimeMillis":1792112400123}"""
        assertThat(m.readValue(json, StockEventDetectedEvent::class.java))
            .isEqualTo(StockEventDetectedEvent(321, 5930, "PRICE_SPIKE", 80, 1792112400123))
    }

    @Test
    fun `market ticks — worker 형식(시각 숫자)과 Go gateway 형식(시각 문자열)을 같은 틱으로 브로드캐스트한다`() {
        val worker = """{"stockId":5930,"symbol":"005930","market":"KOSPI","price":71200.50,"volume":1200,"tradeTime":1792112400.123456789,""" +
            """"generatedAt":1792112400.200000000,"marketStatus":"OPEN","seq":42,"source":"KIS"}"""
        val go = """{"stockId":5930,"symbol":"005930","market":"KOSPI","price":71200.5,"volume":1200,""" +
            """"tradeTime":"2026-10-16T01:00:00.123456789Z","generatedAt":"2026-10-16T01:00:00.2Z","seq":42,"source":"KIS"}"""

        val published = mutableListOf<Any>()
        val publisher = mockk<ApplicationEventPublisher>()
        every { publisher.publishEvent(capture(slot<Any>())) } answers { published += firstArg<Any>() }
        val consumer = MarketTickBroadcastConsumer(mockk(relaxed = true), publisher, mockk(relaxed = true), mockk(relaxed = true), SimpleMeterRegistry())

        consumer.onTick(ConsumerRecord("market.ticks", 0, 0L, "5930", worker))
        consumer.onTick(ConsumerRecord("market.ticks", 0, 1L, "5930", go))

        val generatedAt = Instant.parse("2026-10-16T01:00:00.200Z")
        assertThat(published).containsExactly(
            MarketTickReceivedEvent(PriceTick(5930, "005930", BigDecimal("71200.50"), 1200, t), TickProvenance(PriceSource.KIS, "OPEN", generatedAt)),
            // Go는 marketStatus를 보내지 않는다(null) — 조건부 주문 평가기는 정규장 판정에 실패해 발동하지 않는다(ADR-055 fail-closed)
            MarketTickReceivedEvent(PriceTick(5930, "005930", BigDecimal("71200.5"), 1200, t), TickProvenance(PriceSource.KIS, null, generatedAt)),
        )
    }

    @Test
    fun `stock price Redis — worker RedisTickWriter가 쓴 값을 읽는다(시각은 toString 문자열)`() = withBootMapper { m ->
        val redis = mockk<StringRedisTemplate>()
        every { redis.keys("stock:price:*:005930") } returns setOf("stock:price:KOSPI:005930")
        every { redis.opsForValue().get("stock:price:KOSPI:005930") } returns
            """{"stockId":5930,"symbol":"005930","price":71200.50,"volume":1200,"tradeTime":"2026-10-16T01:00:00.123456789Z"}"""

        // Map으로 읽어 가격이 Double을 거친다 — 소수 자릿수가 줄어든다(71200.50 → 71200.5). 지금 동작 그대로 고정한다
        assertThat(RedisPriceCache(redis, m).getLatestPrice(5930, "005930"))
            .isEqualTo(PriceTick(5930, "005930", BigDecimal("71200.5"), 1200, t))
    }
}
