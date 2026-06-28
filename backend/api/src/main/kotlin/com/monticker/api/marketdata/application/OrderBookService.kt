package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.infrastructure.orderbook.DataSource
import com.monticker.api.marketdata.infrastructure.orderbook.KisOrderBookProvider
import com.monticker.api.marketdata.infrastructure.orderbook.MockOrderBookProvider
import com.monticker.api.marketdata.infrastructure.orderbook.YahooFinanceOrderBookProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class OrderBookService(
    private val jdbc: JdbcTemplate,
    private val kisProvider: KisOrderBookProvider,
    private val yahooProvider: YahooFinanceOrderBookProvider?,   // @ConditionalOnProperty — 없을 수 있음
    private val mockProvider: MockOrderBookProvider,
) {
    fun getOrderBook(stockId: Long): OrderBookResponse {
        val row = jdbc.queryForMap("SELECT symbol, name, market FROM stocks WHERE id = ?", stockId)
        val symbol = row["symbol"] as String
        val market = (row["market"] as? String) ?: "KOSPI"

        val currentPrice: BigDecimal = jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId,
        ) ?: throw IllegalArgumentException("현재가 없음: stockId=$stockId")

        // 우선순위: KIS 실시간 → Yahoo Finance → Mock
        val snapshot =
            kisProvider.getOrderBook(symbol, market, currentPrice)
                ?: yahooProvider?.getOrderBook(symbol, market, currentPrice)
                ?: mockProvider.getOrderBook(symbol, market, currentPrice)

        val asks = snapshot.asks.map { OrderBookLevel(it.price, it.quantity, it.price * BigDecimal(it.quantity)) }
        val bids = snapshot.bids.map { OrderBookLevel(it.price, it.quantity, it.price * BigDecimal(it.quantity)) }

        return OrderBookResponse(
            stockId      = stockId,
            symbol       = symbol,
            currentPrice = currentPrice,
            asks         = asks,
            bids         = bids,
            updatedAt    = snapshot.updatedAt,
            source       = snapshot.source.name,
        )
    }

    private operator fun BigDecimal.times(other: BigDecimal) = this.multiply(other)
}

data class OrderBookResponse(
    val stockId: Long,
    val symbol: String,
    val currentPrice: BigDecimal,
    val asks: List<OrderBookLevel>,
    val bids: List<OrderBookLevel>,
    val updatedAt: Instant,
    val source: String,
)

data class OrderBookLevel(val price: BigDecimal, val quantity: Long, val amount: BigDecimal)
