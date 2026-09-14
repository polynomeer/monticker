package com.monticker.worker.summary

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

class MarketSummaryPublisherTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val publisher = MarketSummaryPublisher(jdbc, mockk<StringRedisTemplate>(relaxed = true), "localhost:1")

    private fun row(id: Long, price: Long, prev: Long?, amount: Long) =
        MarketSummaryPublisher.Row(id, "S$id", "N$id", "KOSPI", BigDecimal.valueOf(price), prev?.let { BigDecimal.valueOf(it) }, 1, BigDecimal.valueOf(amount))

    @Test
    fun `상승 하락 집계와 거래대금 상위 정렬`() {
        every { jdbc.query(any<String>(), any<RowMapper<MarketSummaryPublisher.Row>>()) } returns listOf(
            row(1, 110, 100, 500), row(2, 90, 100, 900), row(3, 100, 100, 100), row(4, 50, null, 50) /* 전일 없음 */, row(5, 0, 100, 0) /* 시세 없음 */,
        )
        val s = publisher.compute()
        assertThat(s["stockCount"]).isEqualTo(4)          // 시세 0인 종목 제외
        assertThat(s["advancers"]).isEqualTo(1)
        assertThat(s["decliners"]).isEqualTo(1)
        assertThat(s["unchanged"]).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val top = s["topByAmount"] as List<Map<String, Any?>>
        assertThat(top.map { it["stockId"] }).containsExactly(2L, 1L, 3L, 4L)
        @Suppress("UNCHECKED_CAST")
        assertThat(((s["topGainers"] as List<Map<String, Any?>>).first()["changeRate"] as Double)).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.001))
    }
}
