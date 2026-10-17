package com.monticker.worker.common

import com.monticker.worker.detector.DetectedEvent
import com.monticker.worker.detector.DetectedEventType
import com.monticker.worker.kafka.AlertTriggeredMessage
import com.monticker.worker.kafka.TickProcessedMessage
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.notification.UserNotificationMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * worker가 Kafka로 주고받는 JSON의 **지금 형식**을 고정한다(docs/dependency-upgrade-plan.md B4-3).
 *
 * 롤링 배포 중에는 구버전과 신버전이 같은 토픽에서 서로의 메시지를 읽는다. Jackson 3(Spring Boot 4)는 기본값이 다르다
 * — `Instant`를 숫자 대신 ISO 문자열로, 속성을 알파벳순으로 쓴다. 이 테스트가 깨지면 와이어 형식이 바뀐 것이다:
 * 의도한 변경이면 소비자가 두 형식을 다 읽는지 먼저 확인하고 고친다.
 *
 * - 쓰기: [WireJson]이 만드는 문자열을 **그대로** 비교한다(키 순서·숫자 표기까지).
 * - 읽기: 구버전 worker 형식, Go market-gateway 형식(시각이 RFC3339 문자열, marketStatus 없음), 새 필드가 없던
 *   이전 형식을 모두 같은 값으로 읽는지 본다.
 */
class WireFormatGoldenTest {

    private val mapper = WireJson.mapper
    private val t = Instant.parse("2026-10-16T01:00:00.123456789Z")

    // ── market.ticks ─────────────────────────────────────────────────────────

    private val tick = GeneratedTick(
        stockId = 5930, symbol = "005930", market = "KOSPI", price = BigDecimal("71200.50"), volume = 1200,
        tradeTime = t, generatedAt = Instant.parse("2026-10-16T01:00:00.200Z"), marketStatus = "OPEN", seq = 42, source = "KIS",
    )

    /** worker(MarketTickScheduler·KIS·Toss 구독기)가 쓰는 형식 — 시각은 초.나노초 숫자 */
    private val tickJson =
        """{"stockId":5930,"symbol":"005930","market":"KOSPI","price":71200.50,"volume":1200,"tradeTime":1792112400.123456789,""" +
            """"generatedAt":1792112400.200000000,"marketStatus":"OPEN","seq":42,"source":"KIS"}"""

    /** Go market-gateway(services/market-gateway/internal/tick)가 쓰는 형식 — 시각은 RFC3339Nano 문자열, marketStatus 없음 */
    private val goTickJson =
        """{"stockId":5930,"symbol":"005930","market":"KOSPI","price":71200.5,"volume":1200,""" +
            """"tradeTime":"2026-10-16T01:00:00.123456789Z","generatedAt":"2026-10-16T01:00:00.2Z","seq":42,"source":"KIS"}"""

    @Test
    fun `market ticks — worker가 쓰는 형식`() {
        assertThat(mapper.writeValueAsString(tick)).isEqualTo(tickJson)
    }

    @Test
    fun `market ticks — worker 형식과 Go gateway 형식을 같은 틱으로 읽는다`() {
        assertThat(mapper.readValue(tickJson, GeneratedTick::class.java)).isEqualTo(tick)
        // Go는 가격을 float64로 써서 소수 자릿수가 줄고(71200.5), marketStatus를 보내지 않는다(기본값 OPEN)
        assertThat(mapper.readValue(goTickJson, GeneratedTick::class.java)).isEqualTo(tick.copy(price = BigDecimal("71200.5")))
    }

    @Test
    fun `market ticks — 모르는 필드는 무시한다(생산자가 먼저 배포돼도 소비자가 DLT로 보내지 않는다)`() {
        val withNewField = tickJson.dropLast(1) + ""","someFutureField":true}"""
        assertThat(mapper.readValue(withNewField, GeneratedTick::class.java)).isEqualTo(tick)
    }

    // ── market.tick-processed ────────────────────────────────────────────────

    @Test
    fun `market tick-processed — 쓰고 읽는다`() {
        val json = """{"stockId":5930,"price":71200.50}"""
        assertThat(mapper.writeValueAsString(TickProcessedMessage(5930, BigDecimal("71200.50")))).isEqualTo(json)
        assertThat(mapper.readValue(json, TickProcessedMessage::class.java)).isEqualTo(TickProcessedMessage(5930, BigDecimal("71200.50")))
    }

    @Test
    fun `모르는 필드는 어노테이션 없는 메시지에서도 무시한다(매퍼 설정 — 컨슈머가 뜨는 순서와 무관)`() {
        assertThat(mapper.readValue("""{"stockId":5930,"price":71200.50,"someFutureField":1}""", TickProcessedMessage::class.java))
            .isEqualTo(TickProcessedMessage(5930, BigDecimal("71200.50")))
        val alert = """{"ruleId":9,"userId":7,"stockId":5930,"ruleType":"PRICE_ABOVE","conditionJson":"{}","price":1,"someFutureField":"x"}"""
        assertThat(mapper.readValue(alert, AlertTriggeredMessage::class.java).ruleId).isEqualTo(9)
    }

    // ── notify.commands ──────────────────────────────────────────────────────

    @Test
    fun `notify commands — 쓰고 읽는다`() {
        val msg = AlertTriggeredMessage(9, 7, 5930, "PRICE_ABOVE", """{"price":70000}""", BigDecimal("71200.50"))
        val json = """{"ruleId":9,"userId":7,"stockId":5930,"ruleType":"PRICE_ABOVE","conditionJson":"{\"price\":70000}","price":71200.50}"""
        assertThat(mapper.writeValueAsString(msg)).isEqualTo(json)
        assertThat(mapper.readValue(json, AlertTriggeredMessage::class.java)).isEqualTo(msg)
    }

    // ── market.events (소비자 없음 — 형식만 고정) ──────────────────────────────

    @Test
    fun `market events — worker가 쓰는 형식`() {
        val event = DetectedEvent(5930, DetectedEventType.PRICE_SPIKE, "급등", "5분 +3.2%", t, 80, mapOf("changeRate" to 3.2, "window" to "5m"))
        assertThat(mapper.writeValueAsString(event)).isEqualTo(
            """{"stockId":5930,"eventType":"PRICE_SPIKE","title":"급등","description":"5분 +3.2%","eventTime":1792112400.123456789,""" +
                """"importanceScore":80,"metadataJson":{"changeRate":3.2,"window":"5m"}}""",
        )
    }

    // ── market.summary (worker → Redis·Kafka → api → 브라우저 WebSocket) ────────

    @Test
    fun `market summary — 웹이 읽는 형식(BigDecimal 자릿수·Double 그대로)`() {
        val summary = mapOf(
            "asOf" to "2026-10-16T01:00:00Z", "stockCount" to 2,
            "topByAmount" to listOf(mapOf("stockId" to 5930L, "price" to BigDecimal("71200.5000"), "changeRate" to 1.25,
                "volume" to 1200L, "amount" to BigDecimal("85440600.0000"))),
        )
        assertThat(mapper.writeValueAsString(summary)).isEqualTo(
            """{"asOf":"2026-10-16T01:00:00Z","stockCount":2,"topByAmount":[{"stockId":5930,"price":71200.5000,"changeRate":1.25,""" +
                """"volume":1200,"amount":85440600.0000}]}""",
        )
    }

    // ── notify.user (api → worker, ADR-065) ──────────────────────────────────

    @Test
    fun `notify user — api가 쓰는 형식과 category 이전 형식을 읽는다`() {
        val current = """{"userId":7,"category":"CONDITIONAL_ORDER","title":"t","body":"b","dedupKey":"k","data":{"conditionalOrderId":12}}"""
        val beforeCategory = """{"userId":7,"title":"t","body":"b","dedupKey":"k","data":{"conditionalOrderId":12}}"""
        val expected = UserNotificationMessage(7, "t", "b", "k", mapOf("conditionalOrderId" to 12), "CONDITIONAL_ORDER")
        assertThat(mapper.readValue(current, UserNotificationMessage::class.java)).isEqualTo(expected)
        assertThat(mapper.readValue(beforeCategory, UserNotificationMessage::class.java)).isEqualTo(expected.copy(category = null))
    }
}
