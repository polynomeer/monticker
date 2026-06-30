package com.monticker.api.marketdata.application

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

class VwapServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val service = VwapService(jdbc)

    @Test
    fun `getDailyVwap computes price-volume-weighted average from the aggregated row`() {
        every {
            jdbc.queryForMap(any<String>(), any<Long>(), any())
        } returns mapOf(
            "price_volume" to 700_000_0.0, // e.g. sum(close*volume)
            "total_volume" to 1000L,
            "candle_count" to 60,
        )

        val result = service.getDailyVwap(1L)

        // vwap = price_volume / total_volume = 7,000,000 / 1000 = 7000
        assertThat(result.vwap).isEqualByComparingTo(BigDecimal("7000.0000"))
        assertThat(result.totalVolume).isEqualTo(1000L)
        assertThat(result.candleCount).isEqualTo(60)
    }

    @Test
    fun `getDailyVwap returns zero when there is no volume yet today`() {
        every {
            jdbc.queryForMap(any<String>(), any<Long>(), any())
        } returns mapOf("price_volume" to 0.0, "total_volume" to 0L, "candle_count" to 0)

        val result = service.getDailyVwap(1L)

        assertThat(result.vwap).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `getDailyVwap handles missing map keys gracefully by defaulting to zero`() {
        every {
            jdbc.queryForMap(any<String>(), any<Long>(), any())
        } returns emptyMap()

        val result = service.getDailyVwap(1L)

        assertThat(result.vwap).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.totalVolume).isEqualTo(0L)
        assertThat(result.candleCount).isEqualTo(0)
    }

    @Test
    fun `getVwapSeries computes a cumulative vwap point per row`() {
        every {
            jdbc.query(any<String>(), any<RowMapper<VwapPoint>>(), any<Long>(), any())
        } returns listOf(
            VwapPoint(time = 1000L, vwap = BigDecimal("7000.0000")),
            VwapPoint(time = 1060L, vwap = BigDecimal("7050.0000")),
        )

        val result = service.getVwapSeries(1L)

        assertThat(result).hasSize(2)
        assertThat(result[0].vwap).isEqualByComparingTo(BigDecimal("7000.0000"))
        assertThat(result[1].vwap).isEqualByComparingTo(BigDecimal("7050.0000"))
    }

    @Test
    fun `getVwapSeries returns an empty list when there is no candle data yet`() {
        every {
            jdbc.query(any<String>(), any<RowMapper<VwapPoint>>(), any<Long>(), any())
        } returns emptyList()

        val result = service.getVwapSeries(1L)

        assertThat(result).isEmpty()
    }
}
