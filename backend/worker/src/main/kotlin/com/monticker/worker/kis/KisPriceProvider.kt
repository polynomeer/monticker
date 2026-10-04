package com.monticker.worker.kis

import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.MarketSchedule
import com.monticker.worker.marketdata.TickSource
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class KisPriceProvider(
    private val kisClient: KisClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // KOSPI/KOSDAQ 종목만 (KIS 국내 API 대상)
    fun fetchTicks(): List<GeneratedTick> {
        if (!kisClient.isConfigured) return emptyList()

        val stocks = fetchKoreanStocks()
        return stocks.mapNotNull { (symbol, stockId) ->
            try {
                val price = kisClient.fetchPrice(symbol) ?: return@mapNotNull null
                GeneratedTick(
                    stockId   = stockId,
                    symbol    = symbol,
                    market    = "KOSPI",
                    price     = price.price,
                    volume    = price.volume,
                    tradeTime = Instant.now(),
                    marketStatus = MarketSchedule.getTickConfig(symbol, "KOSPI").status.name,
                    source    = TickSource.KIS,
                )
            } catch (e: Exception) {
                log.warn("KIS tick fetch failed for {}: {}", symbol, e.message)
                null
            }
        }
    }

    private fun fetchKoreanStocks(): List<Pair<String, Long>> =
        jdbc.query(
            "SELECT id, symbol FROM stocks WHERE market IN ('KOSPI', 'KOSDAQ') AND is_active = true LIMIT 20",
        ) { rs, _ -> rs.getString("symbol") to rs.getLong("id") }
}
