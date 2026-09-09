package com.monticker.api.ai

import com.monticker.api.marketdata.application.CandleService
import com.monticker.api.marketdata.application.MarketDataService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/** StockSummaryService/OrderProposalService가 공유하는 당일 가격 동향 조회 — 두 곳이 같은 알고리즘을 따로 유지보수하지 않도록 분리. */
@Service
class PriceActionService(
    private val marketDataService: MarketDataService,
    private val candleService: CandleService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class PriceAction(
        val current: BigDecimal,
        val dayOpen: BigDecimal,
        val dayHigh: BigDecimal,
        val dayLow: BigDecimal,
        val prevClose: BigDecimal?,
    )

    /** 오늘자 시가·고가·저가와 전일 종가를 candles_1d에서 가져와 가격 동향을 구성한다. */
    fun getPriceAction(stockId: Long, symbol: String): PriceAction? {
        return try {
            val latest = marketDataService.getLatestPrice(stockId, symbol) ?: return null
            val recentDaily = candleService.getCandles(
                stockId, "1d",
                from = Instant.now().minus(5, ChronoUnit.DAYS),
            ).sortedBy { it.time }
            val today = recentDaily.lastOrNull() ?: return null
            val prevClose = recentDaily.dropLast(1).lastOrNull()?.close
            PriceAction(
                current   = latest.price,
                dayOpen   = today.open,
                dayHigh   = today.high,
                dayLow    = today.low,
                prevClose = prevClose,
            )
        } catch (e: Exception) {
            log.debug("Price action lookup failed for stockId={}: {}", stockId, e.message)
            null
        }
    }

    fun pctChange(from: BigDecimal, to: BigDecimal): String {
        if (from.signum() == 0) return "0.00"
        return to.subtract(from).divide(from, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}
