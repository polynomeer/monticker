package com.monticker.worker.stock

import com.monticker.worker.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 종목 마스터 동기화를 실제 스키마(api 마이그레이션)에 실행한다. 예전 upsert는 없는 컬럼(updated_at)을 써서 매번 SQL 오류였는데,
 * JdbcTemplate을 목으로 둔 단위 테스트로는 잡히지 않았다.
 */
class StockMasterCollectorIntegrationTest : PostgresIntegrationTest() {

    private val krx = mockk<KrxStockClient>()
    private val collector by lazy { StockMasterCollector(krx, jdbcTemplate, enabled = true) }
    private val code = "Q%05d".format(System.nanoTime() % 100_000)

    private fun rows(symbol: String) = jdbcTemplate.queryForList(
        "SELECT market, name, is_active FROM stocks WHERE symbol = ? ORDER BY id", symbol,
    )

    @Test
    fun `inserts a new listing and updates the name of an existing one`() {
        every { krx.fetchStocks() } returns listOf(KrxStockItem(code, "새 종목", "KOSPI", "IT"))
        collector.collect()
        every { krx.fetchStocks() } returns listOf(KrxStockItem(code, "바뀐 이름", "KOSPI", "IT"))
        collector.collect()

        assertThat(rows(code)).singleElement().satisfies({ r ->
            assertThat(r["market"]).isEqualTo("KOSPI")
            assertThat(r["name"]).isEqualTo("바뀐 이름")
            assertThat(r["is_active"]).isEqualTo(true)
        })
    }

    @Test
    fun `a market transfer deactivates the row under the old KRX market`() {
        every { krx.fetchStocks() } returns listOf(KrxStockItem(code, "이전상장", "KOSDAQ", "IT"))
        collector.collect()
        every { krx.fetchStocks() } returns listOf(KrxStockItem(code, "이전상장", "KOSPI", "IT"))
        collector.collect()

        assertThat(rows(code).map { it["market"] to it["is_active"] })
            .containsExactly("KOSDAQ" to false, "KOSPI" to true)
    }

    @Test
    fun `the same ticker on a US market is left alone`() {
        val us = "QZ" + System.nanoTime() % 10_000
        jdbcTemplate.update(
            "INSERT INTO stocks (symbol, name, market, exchange, country, currency) VALUES (?, 'us', 'NASDAQ', 'NASDAQ', 'US', 'USD')", us,
        )
        every { krx.fetchStocks() } returns listOf(KrxStockItem(us, "kr", "KOSPI", "IT"))
        collector.collect()

        assertThat(rows(us).map { it["market"] to it["is_active"] })
            .containsExactlyInAnyOrder("NASDAQ" to true, "KOSPI" to true)
    }
}
