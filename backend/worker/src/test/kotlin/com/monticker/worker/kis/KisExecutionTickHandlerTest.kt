package com.monticker.worker.kis

import com.monticker.worker.kafka.TickKafkaProducer
import com.monticker.worker.marketdata.GeneratedTick
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class KisExecutionTickHandlerTest {

    // H0STCNT0 46필드 중 이 핸들러가 실제로 읽는 4개(idx0,1,2,12)만 채우고 나머지는
    // 빈 문자열로 채운다 — 필드 개수가 부족하면 handle()이 조용히 무시하기 때문에
    // 파싱 로직이 실제 인덱스를 쓰는지 검증하려면 최소 15개 필드가 있어야 한다.
    private fun h0stcnt0Frame(symbol: String, time: String, price: String, volume: String): List<String> {
        val fields = MutableList(20) { "" }
        fields[0] = symbol
        fields[1] = time
        fields[2] = price
        fields[12] = volume
        return listOf("encType", "H0STCNT0", "1") + fields
    }

    // KisCoverageProvider의 targets/coveredStockIds/bySymbol은 val이라 리플렉션 없이
    // 직접 재구성할 수 없다 — "kis" 모드로 jdbc mock을 태워 실제 프로덕션 경로 그대로 구성한다.
    private fun coverageViaKisMode(target: KisTickTarget): KisCoverageProvider {
        val jdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<org.springframework.jdbc.core.RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<org.springframework.jdbc.core.RowMapper<Any>>()
                val rs = mockk<java.sql.ResultSet> {
                    every { getLong("id") } returns target.stockId
                    every { getString("symbol") } returns target.symbol
                    every { getString("market") } returns target.market
                }
                listOf(mapper.mapRow(rs, 0))
            }
        }
        return KisCoverageProvider(jdbc, "kis")
    }

    @Test
    fun `H0STCNT0 프레임에서 가격과 거래량을 읽어 market ticks로 발행한다`() {
        val coverage = coverageViaKisMode(KisTickTarget(1L, "005930", "KOSPI"))
        val producer = mockk<TickKafkaProducer>(relaxed = true)
        val handler = KisExecutionTickHandler(coverage, producer)

        handler.handle(h0stcnt0Frame(symbol = "005930", time = "093015", price = "71500", volume = "120"))

        val slot = slot<GeneratedTick>()
        verify { producer.publish(capture(slot)) }
        val tick = slot.captured
        assertThat(tick.stockId).isEqualTo(1L)
        assertThat(tick.symbol).isEqualTo("005930")
        assertThat(tick.market).isEqualTo("KOSPI")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("71500"))
        assertThat(tick.volume).isEqualTo(120L)
    }

    @Test
    fun `커버리지에 없는 종목코드는 무시한다`() {
        val coverage = coverageViaKisMode(KisTickTarget(1L, "005930", "KOSPI"))
        val producer = mockk<TickKafkaProducer>(relaxed = true)
        val handler = KisExecutionTickHandler(coverage, producer)

        handler.handle(h0stcnt0Frame(symbol = "000660", time = "093015", price = "180000", volume = "10"))

        verify(exactly = 0) { producer.publish(any()) }
    }

    @Test
    fun `필드 개수가 부족한 프레임은 무시한다`() {
        val coverage = coverageViaKisMode(KisTickTarget(1L, "005930", "KOSPI"))
        val producer = mockk<TickKafkaProducer>(relaxed = true)
        val handler = KisExecutionTickHandler(coverage, producer)

        handler.handle(listOf("encType", "H0STCNT0", "1", "005930"))

        verify(exactly = 0) { producer.publish(any()) }
    }
}
