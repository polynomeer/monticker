package com.monticker.api.screener.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScreenerCriteriaTest {

    @Test
    fun `normalizes sectors and events so cache keys are order independent`() {
        val a = ScreenerCriteria(sectors = listOf(" 반도체", "금융", "반도체"), events = listOf(ScreenerEventFilter.QUANT_SIGNAL, ScreenerEventFilter.NEWS)).normalized()
        val b = ScreenerCriteria(sectors = listOf("금융", "반도체"), events = listOf(ScreenerEventFilter.NEWS, ScreenerEventFilter.QUANT_SIGNAL)).normalized()

        assertThat(a).isEqualTo(b)
        assertThat(a.sectors).containsExactly("금융", "반도체")
        assertThat(a.cacheKey()).isEqualTo(b.cacheKey())
    }

    @Test
    fun `rejects values outside whitelists and ranges`() {
        val bad = listOf(
            ScreenerCriteria(market = "kospi; DROP TABLE"),
            ScreenerCriteria(marketCapTier = "huge"),
            ScreenerCriteria(sort = "random()"),
            ScreenerCriteria(minChange = -150.0),
            ScreenerCriteria(maxChange = Double.NaN),
            ScreenerCriteria(minChange = 5.0, maxChange = 1.0),
            ScreenerCriteria(minVolMult = -1.0),
            ScreenerCriteria(minVolMult = Double.POSITIVE_INFINITY),
            ScreenerCriteria(sectors = (1..21).map { "s$it" }),
            ScreenerCriteria(sectors = listOf("x".repeat(101))),
        )
        bad.forEach { c ->
            assertThatThrownBy { c.normalized() }.`as`(c.toString()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `parses event tokens case-insensitively and rejects unknown ones`() {
        assertThat(ScreenerCriteria.parseEvents("news, quant_signal")).containsExactly(ScreenerEventFilter.NEWS, ScreenerEventFilter.QUANT_SIGNAL)
        assertThat(ScreenerCriteria.parseEvents(null)).isEmpty()
        assertThatThrownBy { ScreenerCriteria.parseEvents("NEWS,EARNINGS") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `volume multiple lateral is only needed for its filter or sort`() {
        assertThat(ScreenerCriteria().needsVolumeMultipleInQuery).isFalse()
        assertThat(ScreenerCriteria(minVolMult = 2.0).needsVolumeMultipleInQuery).isTrue()
        assertThat(ScreenerCriteria(sort = "volmult").needsVolumeMultipleInQuery).isTrue()
        assertThat(ScreenerCriteria(events = listOf(ScreenerEventFilter.DISCLOSURE, ScreenerEventFilter.QUANT_SIGNAL)).stockEventTypes)
            .containsExactly("DISCLOSURE_PUBLISHED")
    }
}
