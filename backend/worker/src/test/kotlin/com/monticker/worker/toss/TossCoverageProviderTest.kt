package com.monticker.worker.toss

import com.monticker.worker.kis.KisCoverageProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

class TossCoverageProviderTest {

    private fun krRows(vararg rows: Pair<Long, String>): JdbcTemplate = mockk {
        every { query(any<String>(), any<RowMapper<Any>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            val mapper = secondArg<RowMapper<Any>>()
            rows.mapIndexed { i, (id, symbol) ->
                val rs = mockk<ResultSet> {
                    every { getLong("id") } returns id
                    every { getString("symbol") } returns symbol
                    every { getString("market") } returns "KOSPI"
                }
                mapper.mapRow(rs, i)
            }
        }
    }

    @Test
    fun `ingestion source에 toss가 없으면 아무것도 구독하지 않는다`() {
        val kisCoverage = KisCoverageProvider(mockk(), "internal")
        val coverage = TossCoverageProvider(krRows(1L to "005930"), "internal", kisCoverage)

        assertThat(coverage.allTargets).isEmpty()
        assertThat(coverage.coveredStockIds).isEmpty()
    }

    @Test
    fun `KIS가 이미 커버하는 종목은 Toss 국내 대상에서 제외한다`() {
        // KIS가 stockId=1을 커버 중이라면, 같은 국내 종목 목록에서 Toss는 stockId=2만 가져가야 한다(ADR-031).
        val kisJdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val rs = mockk<ResultSet> {
                    every { getLong("id") } returns 1L
                    every { getString("symbol") } returns "005930"
                    every { getString("market") } returns "KOSPI"
                }
                listOf(mapper.mapRow(rs, 0))
            }
        }
        val kisCoverage = KisCoverageProvider(kisJdbc, "kis")

        val tossJdbc = krRows(1L to "005930", 2L to "000660")
        val coverage = TossCoverageProvider(tossJdbc, "toss", kisCoverage)

        assertThat(coverage.krTargets.map { it.stockId }).containsExactly(2L)
    }
}
