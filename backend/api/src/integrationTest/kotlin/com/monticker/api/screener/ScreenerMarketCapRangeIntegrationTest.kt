package com.monticker.api.screener

import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.infrastructure.ScreenerRepository
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 시가총액 범위(minCap/maxCap) — 양 끝 포함, 시가총액 없는 종목은 범위를 걸 때만 빠진다, 구간(marketCapTier)과 AND.
 * 목록·총수(LATERAL 없는 빠른 경로와 계산 컬럼 경로) 모두 같은 결과인지 본다. 고유 섹터로 내 행만 본다.
 */
class ScreenerMarketCapRangeIntegrationTest : PostgresIntegrationTest() {

    private val repo by lazy { ScreenerRepository(jdbcTemplate) }

    private fun stock(sector: String, marketCap: Long?, withFundamentals: Boolean = true): Long {
        val id = jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange, sector, is_active) VALUES (?, '시총테스트', 'KOSPI', 'X', ?, true) RETURNING id",
            Long::class.java, "MC" + UUID.randomUUID().toString().take(8), sector,
        )!!
        if (withFundamentals) {
            jdbcTemplate.update("INSERT INTO stock_fundamentals (stock_id, market_cap) VALUES (?, ?)", id, marketCap)
        }
        return id
    }

    private fun ids(c: ScreenerCriteria) = repo.findItems(c.normalized(), 50, 0).map { it.stockId }.toSet()
    private fun count(c: ScreenerCriteria) = repo.count(c.normalized())

    @Test
    fun `range is inclusive and excludes unknown caps only when applied`() {
        val sector = "시총-" + UUID.randomUUID().toString().take(8)
        val small = stock(sector, 50_000_000_000L)        // 500억
        val mid = stock(sector, 300_000_000_000L)         // 3000억
        val large = stock(sector, 2_000_000_000_000L)     // 2조
        val nullCap = stock(sector, null)
        val noRow = stock(sector, null, withFundamentals = false)

        val base = ScreenerCriteria(sectors = listOf(sector))
        assertThat(ids(base)).containsExactlyInAnyOrder(small, mid, large, nullCap, noRow)
        assertThat(count(base)).isEqualTo(5)

        val range = base.copy(minCap = 50_000_000_000L, maxCap = 300_000_000_000L)
        assertThat(ids(range)).containsExactlyInAnyOrder(small, mid)
        assertThat(count(range)).isEqualTo(2)

        val onlyMin = base.copy(minCap = 300_000_000_001L)
        assertThat(ids(onlyMin)).containsExactly(large)
        assertThat(count(onlyMin)).isEqualTo(1)

        // 구간과 함께면 둘 다 만족해야 한다
        val withTier = base.copy(marketCapTier = "mid", maxCap = 200_000_000_000L)
        assertThat(ids(withTier)).isEmpty()
        assertThat(count(withTier)).isEqualTo(0)

        // 계산 컬럼 조건이 있는 총수 경로(CTE)도 같은 결과
        val computed = range.copy(minChange = -100.0)
        assertThat(count(computed)).isEqualTo(ids(computed).size)
    }
}
