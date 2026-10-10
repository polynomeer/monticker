package com.monticker.worker.stock

import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class StockMasterCollectorTest {

    private val krxClient = mockk<KrxStockClient>()
    private val jdbc      = mockk<JdbcTemplate>(relaxed = true)
    private val collector = StockMasterCollector(krxClient, jdbc, enabled = true)

    @Test
    fun `upserts stocks returned by KRX client`() {
        every { krxClient.fetchStocks() } returns listOf(
            KrxStockItem("005930", "삼성전자", "KOSPI", "전기전자"),
            KrxStockItem("000660", "SK하이닉스", "KOSPI", "전기전자"),
        )

        collector.collect()

        verify(exactly = 2) { jdbc.update(match<String> { it.contains("INSERT INTO stocks") }, *anyVararg()) }
        // 국내 종목은 다른 국내 시장의 같은 코드 행(이전상장 전 행)을 끈다
        verify(exactly = 2) { jdbc.update(match<String> { it.contains("SET is_active = false") }, *anyVararg()) }
    }

    @Test
    fun `falls back to mock data when KRX returns empty`() {
        every { krxClient.fetchStocks() } returns emptyList()

        collector.collect()

        verify(exactly = MockStockData.stocks.size) { jdbc.update(match<String> { it.contains("INSERT INTO stocks") }, *anyVararg()) }
    }

    @Test
    fun `collectOnStartup skips when enough stocks exist`() {
        every { jdbc.queryForObject(any<String>(), Int::class.java) } returns 100

        collector.collectOnStartup()

        verify(exactly = 0) { krxClient.fetchStocks() }
    }

    @Test
    fun `collectOnStartup triggers collect when few stocks`() {
        every { jdbc.queryForObject(any<String>(), Int::class.java) } returns 5
        every { krxClient.fetchStocks() } returns MockStockData.stocks

        collector.collectOnStartup()

        verify { krxClient.fetchStocks() }
    }

    @Test
    fun `does nothing while the ops switch is off`() {
        val off = StockMasterCollector(krxClient, jdbc)

        off.collect()
        off.collectOnStartup()

        verify(exactly = 0) { krxClient.fetchStocks() }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }
}
