package com.monticker.worker.toss

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.kafka.TickKafkaProducer
import com.monticker.worker.kis.KisCoverageProvider
import com.monticker.worker.marketdata.GeneratedTick
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.ResultSet

class TossExecutionTickHandlerTest {

    private val mapper = ObjectMapper()
    private val noKisCoverage = KisCoverageProvider(mockk(), "internal")

    // TossCoverageProvider의 usTargets/krTargets는 val이라 리플렉션 없이 직접 재구성할 수
    // 없다 — "toss" 모드로 jdbc mock을 태워 실제 프로덕션 경로 그대로 구성한다.
    private fun coverageWithUsTarget(stockId: Long, symbol: String, market: String): TossCoverageProvider {
        val jdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val rs = mockk<ResultSet> {
                    every { getLong("id") } returns stockId
                    every { getString("symbol") } returns symbol
                    every { getString("market") } returns market
                }
                listOf(mapper.mapRow(rs, 0))
            }
        }
        return TossCoverageProvider(jdbc, "toss", noKisCoverage)
    }

    private fun tradeMessage(topic: String, price: String, volume: String, timestamp: String) =
        mapper.readTree(
            """{"type":"message","topic":"$topic","data":{"price":"$price","volume":"$volume","timestamp":"$timestamp","currency":"USD"}}"""
        )

    @Test
    fun `trade 메시지에서 topic의 종목코드와 data의 가격 거래량을 읽어 market ticks로 발행한다`() {
        val coverage = coverageWithUsTarget(1L, "AAPL", "NASDAQ")
        val producer = mockk<TickKafkaProducer>(relaxed = true)
        val handler = TossExecutionTickHandler(coverage, producer)

        val message = tradeMessage("trade:us:AAPL", price = "243.26", volume = "8", timestamp = "2026-06-18T23:30:00.000+09:00")
        handler.handle(message["topic"].asText(), message["data"])

        val slot = slot<GeneratedTick>()
        verify { producer.publish(capture(slot)) }
        val tick = slot.captured
        assertThat(tick.stockId).isEqualTo(1L)
        assertThat(tick.symbol).isEqualTo("AAPL")
        assertThat(tick.market).isEqualTo("NASDAQ")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("243.26"))
        assertThat(tick.volume).isEqualTo(8L)
    }

    @Test
    fun `커버리지에 없는 종목코드는 무시한다`() {
        val coverage = coverageWithUsTarget(1L, "AAPL", "NASDAQ")
        val producer = mockk<TickKafkaProducer>(relaxed = true)
        val handler = TossExecutionTickHandler(coverage, producer)

        val message = tradeMessage("trade:us:TSLA", price = "250.00", volume = "5", timestamp = "2026-06-18T23:30:00.000+09:00")
        handler.handle(message["topic"].asText(), message["data"])

        verify(exactly = 0) { producer.publish(any()) }
    }

    @Test
    fun `가격 필드가 숫자가 아니면 무시한다`() {
        val coverage = coverageWithUsTarget(1L, "AAPL", "NASDAQ")
        val producer = mockk<TickKafkaProducer>(relaxed = true)
        val handler = TossExecutionTickHandler(coverage, producer)

        val message = tradeMessage("trade:us:AAPL", price = "N/A", volume = "8", timestamp = "2026-06-18T23:30:00.000+09:00")
        handler.handle(message["topic"].asText(), message["data"])

        verify(exactly = 0) { producer.publish(any()) }
    }
}
