package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.MarketIndexClose
import com.monticker.api.marketdata.domain.MarketIndexQuote
import com.monticker.api.marketdata.infrastructure.MarketIndexRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class MarketIndexServiceTest {

    private val repo = mockk<MarketIndexRepository>()
    private val service = MarketIndexService(repo)
    private val today = LocalDate.of(2026, 10, 6)

    private fun quote(code: String, value: String, prev: String?) =
        MarketIndexQuote(code, code, BigDecimal(value), prev?.let(::BigDecimal), Instant.parse("2026-10-06T01:00:00Z"), "MOCK", true)

    @Test
    fun `returns indices in display order with change rate and spark closes`() {
        every { repo.findQuotes() } returns listOf(quote("USDKRW", "1380", "1370"), quote("KOSPI", "2626", "2600"))
        every { repo.findCloses(any(), any(), any(), MarketIndexService.SPARK_DAYS) } returns
            listOf(MarketIndexClose(today.minusDays(1), BigDecimal("2600"), true))

        val result = service.getIndices(today)

        assertThat(result.map { it.quote.code }).containsExactly("KOSPI", "USDKRW")
        assertThat(result[0].quote.changeRate).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(result[0].closes).hasSize(1)
    }

    @Test
    fun `change rate is null without a previous close`() {
        assertThat(quote("KOSPI", "2600", null).changeRate).isNull()
        assertThat(quote("KOSPI", "2600", null).change).isNull()
    }

    @Test
    fun `closes rejects unknown codes and inverted ranges`() {
        assertThatThrownBy { service.getCloses("DROP TABLE", today.minusDays(5), today) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.getCloses("KOSPI", today, today.minusDays(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `closes are bounded`() {
        every { repo.findCloses("KOSPI", any(), any(), MarketIndexService.MAX_CLOSES) } returns emptyList()

        assertThat(service.getCloses("KOSPI", today.minusYears(10), today)).isEmpty()
    }
}
