package com.monticker.api.marketdata.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Instant

class IntradaySeriesServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val service = IntradaySeriesService(jdbc)

    @Test
    fun `ids are deduplicated, sorted, positive and capped`() {
        assertThat(IntradaySeriesService.normalizeIds(listOf(3, 1, 3, -2, 0, 2))).containsExactly(1L, 2L, 3L)
        assertThat(IntradaySeriesService.normalizeIds((1L..80L).toList())).hasSize(IntradaySeriesService.MAX_STOCKS)
    }

    @Test
    fun `groups rows per stock in request order and keeps stocks without candles`() {
        val t = Instant.parse("2026-10-06T01:00:00Z")
        every { jdbc.query(any<String>(), any<RowMapper<Triple<Long, BigDecimal, Instant>>>(), *anyVararg()) } returns listOf(
            Triple(1L, BigDecimal("100"), t),
            Triple(1L, BigDecimal("101"), t.plusSeconds(600)),
            Triple(2L, BigDecimal("50"), t),
        )

        val result = service.getSeries(listOf(1L, 2L, 3L))

        assertThat(result.map { it.stockId }).containsExactly(1L, 2L, 3L)
        assertThat(result[0].closes).containsExactly(BigDecimal("100"), BigDecimal("101"))
        assertThat(result[0].lastTime).isEqualTo(t.plusSeconds(600))
        assertThat(result[2].closes).isEmpty()
        assertThat(result[2].lastTime).isNull()
    }

    @Test
    fun `empty input does not hit the database and oversized input is rejected`() {
        assertThat(service.getSeries(emptyList())).isEmpty()
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
        assertThatThrownBy { service.getSeries((1L..51L).toList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
