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
            ScreenerCriteria(minCap = -1),
            ScreenerCriteria(maxCap = ScreenerCriteria.MARKET_CAP_CEIL + 1),
            ScreenerCriteria(minCap = 500, maxCap = 100),
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

    // 보안 리뷰 — 섹터를 `|`로 이어 붙인 키는 이스케이프가 없어 ["a|b"]와 ["a","b"]가 같은 캐시 항목을 썼다(공유 캐시).
    @Test
    fun `cache key does not collide when a sector contains the old separator`() {
        val joined = ScreenerCriteria(sectors = listOf("a|b")).normalized()
        val split = ScreenerCriteria(sectors = listOf("a", "b")).normalized()
        assertThat(joined.cacheKey()).isNotEqualTo(split.cacheKey())

        val colon = ScreenerCriteria(sectors = listOf("x:all")).normalized()
        val plain = ScreenerCriteria(sectors = listOf("x")).normalized()
        assertThat(colon.cacheKey()).isNotEqualTo(plain.cacheKey())
    }

    @Test
    fun `cache key is a fixed-length hash`() {
        val key = ScreenerCriteria(sectors = listOf("반도체".repeat(30))).normalized().cacheKey()
        assertThat(key).matches("[0-9a-f]{64}")
    }

    @Test
    fun `accepts KOSPI and KOSDAQ segments in lowercase only`() {
        assertThat(ScreenerCriteria(market = "kospi").normalized().market).isEqualTo("kospi")
        assertThat(ScreenerCriteria(market = "kosdaq").normalized().market).isEqualTo("kosdaq")
        assertThatThrownBy { ScreenerCriteria(market = "KOSPI").normalized() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `market segments match only their own exchange`() {
        val m = com.monticker.api.screener.infrastructure.ScreenerRepository.Companion
        assertThat(m.matchesMarket("kospi", "KOSPI")).isTrue()
        assertThat(m.matchesMarket("kospi", "KOSDAQ")).isFalse()
        assertThat(m.matchesMarket("kosdaq", "KOSDAQ")).isTrue()
        assertThat(m.matchesMarket("kosdaq", "NASDAQ")).isFalse()
        assertThat(m.matchesMarket("domestic", "KOSDAQ")).isTrue()
    }

    @Test
    fun `market cap range accepts equal bounds and marks the fundamentals join`() {
        val c = ScreenerCriteria(minCap = 100_000_000_000L, maxCap = 100_000_000_000L).normalized()
        assertThat(c.hasMarketCapFilter).isTrue()
        assertThat(ScreenerCriteria(maxCap = 0).normalized().hasMarketCapFilter).isTrue()
        assertThat(ScreenerCriteria().hasMarketCapFilter).isFalse()
        assertThat(ScreenerCriteria(marketCapTier = "large").hasMarketCapFilter).isTrue()
        // 범위가 다르면 캐시 키도 다르다
        assertThat(ScreenerCriteria(minCap = 1).normalized().cacheKey()).isNotEqualTo(ScreenerCriteria().normalized().cacheKey())
    }
}
