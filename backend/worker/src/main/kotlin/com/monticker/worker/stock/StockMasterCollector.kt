package com.monticker.worker.stock

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class StockMasterCollector(
    private val krxClient: KrxStockClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 오전 6시 실행
    @Scheduled(cron = "0 0 6 * * *")
    fun collect() {
        log.info("Starting stock master sync...")
        val stocks = krxClient.fetchStocks().ifEmpty {
            log.warn("KRX returned empty — falling back to mock data")
            MockStockData.stocks
        }
        upsert(stocks)
    }

    // 앱 시작 시 1회 실행 (DB에 종목이 5개 이하면 즉시 적재)
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    fun collectOnStartup() {
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM stocks", Int::class.java) ?: 0
        if (count <= 5) {
            log.info("Stocks table has only $count rows — running initial sync")
            collect()
        } else {
            log.info("Stocks table has $count rows — skipping initial sync")
        }
    }

    private fun upsert(stocks: List<KrxStockItem>) {
        var inserted = 0
        var updated  = 0

        for (s in stocks) {
            val (market, exchange, country, currency) = when (s.market) {
                "KOSPI"  -> listOf("KOSPI",  "KRX",    "KR", "KRW")
                "KOSDAQ" -> listOf("KOSDAQ", "KRX",    "KR", "KRW")
                "NASDAQ" -> listOf("NASDAQ", "NASDAQ", "US", "USD")
                "NYSE"   -> listOf("NYSE",   "NYSE",   "US", "USD")
                else     -> listOf(s.market, s.market, "KR", "KRW")
            }

            val rows = jdbc.update(
                """
                INSERT INTO stocks (symbol, name, market, exchange, sector, country, currency, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, true)
                ON CONFLICT (symbol, market) DO UPDATE SET
                    name      = EXCLUDED.name,
                    sector    = EXCLUDED.sector,
                    is_active = true,
                    updated_at = now()
                """,
                s.symbol, s.name, market, exchange, s.sector, country, currency,
            )
            if (rows > 0) inserted++ else updated++
        }

        log.info("Stock master sync complete: inserted={}, skipped={}, total={}", inserted, updated, stocks.size)
    }
}
