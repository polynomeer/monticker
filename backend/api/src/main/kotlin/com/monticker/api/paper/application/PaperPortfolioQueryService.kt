package com.monticker.api.paper.application

import com.monticker.api.common.domain.Price
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
@Transactional(readOnly = true)
class PaperPortfolioQueryService(
    private val accountRepo: PaperAccountRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
) {
    private fun currentPriceMap(stockIds: List<Long>): Map<Long, BigDecimal> {
        if (stockIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """SELECT DISTINCT ON (stock_id) stock_id, close
               FROM candles_1m WHERE stock_id IN (${stockIds.joinToString(",") { "?" }})
               ORDER BY stock_id, candle_time DESC""",
            { rs, _ -> rs.getLong("stock_id") to rs.getBigDecimal("close") },
            *stockIds.toTypedArray(),
        ).toMap()
    }

    private fun stockInfoMap(stockIds: List<Long>): Map<Long, Pair<String, String>> {
        if (stockIds.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, symbol, name FROM stocks WHERE id IN (${stockIds.joinToString(",") { "?" }})",
            { rs, _ -> Triple(rs.getLong("id"), rs.getString("symbol"), rs.getString("name")) },
            *stockIds.toTypedArray(),
        ).associate { it.first to (it.second to it.third) }
    }

    fun buildHoldings(userId: Long): List<HoldingResponse> {
        // CQRS 읽기모델: paper_trades 집계 대신 portfolio_positions를 직접 읽는다.
        val positions = jdbc.query(
            "SELECT stock_id, net_qty, avg_buy_price FROM portfolio_positions WHERE user_id = ? AND net_qty > 0",
            { rs, _ -> Triple(rs.getLong("stock_id"), rs.getInt("net_qty"), rs.getBigDecimal("avg_buy_price")) },
            userId,
        )
        if (positions.isEmpty()) return emptyList()

        val stockIds = positions.map { it.first }
        val priceMap = currentPriceMap(stockIds)
        val infoMap  = stockInfoMap(stockIds)

        return positions.mapNotNull { (stockId, qty, avgPrice) ->
            val cur = priceMap[stockId] ?: return@mapNotNull null
            val (symbol, name) = infoMap[stockId] ?: return@mapNotNull null
            val value   = cur.multiply(BigDecimal(qty))
            val cost    = avgPrice.multiply(BigDecimal(qty))
            val pnl     = value - cost
            val pnlRate = if (cost > BigDecimal.ZERO)
                pnl.divide(cost, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 0.0
            HoldingResponse(stockId, symbol, name, qty, avgPrice, cur, value, pnl, pnlRate)
        }
    }

    fun getPortfolio(userId: Long): PortfolioResponse {
        val account   = accountRepo.findByUserId(userId).orElseGet {
            com.monticker.api.paper.domain.PaperAccount(userId = userId)
        }
        val holdings  = buildHoldings(userId)
        val evalValue = holdings.fold(BigDecimal.ZERO) { acc, h -> acc + h.value }
        val totalValue = account.cash.amount + evalValue
        val invested   = holdings.fold(BigDecimal.ZERO) { acc, h -> acc + h.avgPrice.multiply(BigDecimal(h.quantity)) }
        val pnl        = evalValue - invested
        val pnlRate    = if (invested > BigDecimal.ZERO)
            pnl.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 0.0
        return PortfolioResponse(account.cash.amount, totalValue, pnl, pnlRate, holdings)
    }

    fun getHistory(userId: Long): List<TradeHistoryResponse> {
        val trades = tradeRepo.findTop20ByUserIdOrderByTradedAtDesc(userId)
        if (trades.isEmpty()) return emptyList()
        val infoMap = stockInfoMap(trades.map { it.stockId }.distinct())
        return trades.mapNotNull { t ->
            val (symbol, name) = infoMap[t.stockId] ?: return@mapNotNull null
            TradeHistoryResponse(t.id, t.side, t.stockId, symbol, name, t.quantity, t.price, t.amount, t.tradedAt)
        }
    }

    fun getRiskMetrics(userId: Long): RiskMetricsResponse {
        val account  = accountRepo.findByUserId(userId).orElseGet {
            com.monticker.api.paper.domain.PaperAccount(userId = userId)
        }
        val trades   = tradeRepo.findTop20ByUserIdOrderByTradedAtDesc(userId)
        val holdings = buildHoldings(userId)

        if (holdings.isEmpty() && trades.isEmpty()) {
            return RiskMetricsResponse(
                sharpeRatio = 0.0, beta = 1.0, maxDrawdown = 0.0,
                volatility = 0.0, var95 = 0.0,
                winRate = 0.0, totalTrades = 0, avgReturn = 0.0,
                dailyReturns = emptyList(), drawdownSeries = emptyList(),
                hasEnoughData = false,
                message = "거래 내역이 없습니다. 먼저 모의 투자를 시작해보세요.",
            )
        }

        val stockIds = (holdings.map { it.stockId } + trades.map { it.stockId }).distinct()
        if (stockIds.isEmpty()) return RiskMetricsResponse(
            0.0, 1.0, 0.0, 0.0, 0.0, 0.0, trades.size, 0.0,
            emptyList(), emptyList(), false, "종목 데이터 없음",
        )

        val placeholders = stockIds.joinToString(",") { "?" }
        val candles = jdbc.query(
            """SELECT stock_id, DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d, close
               FROM candles_1d WHERE stock_id IN ($placeholders) ORDER BY d, stock_id""",
            { rs, _ -> Triple(rs.getLong("stock_id"), rs.getDate("d").toLocalDate(), rs.getBigDecimal("close").toDouble()) },
            *stockIds.toTypedArray(),
        )

        val priceMap = candles.groupBy { it.second }
            .mapValues { (_, v) -> v.associate { it.first to it.third } }
        val dates = priceMap.keys.sorted()

        if (dates.size < 5) {
            return RiskMetricsResponse(
                sharpeRatio = 0.0, beta = 1.0, maxDrawdown = 0.0,
                volatility = 0.0, var95 = 0.0,
                winRate = 0.0, totalTrades = trades.size, avgReturn = 0.0,
                dailyReturns = emptyList(), drawdownSeries = emptyList(),
                hasEnoughData = false,
                message = "수익률 계산 데이터 부족 (${dates.size}일, 최소 5일 필요)",
            )
        }

        val portfolioPrices  = dates.map { d -> priceMap[d]?.values?.average() ?: 0.0 }
        val portfolioReturns = portfolioPrices.zipWithNext { a, b -> if (a == 0.0) 0.0 else (b - a) / a }

        val initial     = account.cash.amount.toDouble() + holdings.sumOf { it.value.toDouble() }
            .coerceAtLeast(account.cash.amount.toDouble())
        var equity      = initial
        val equityCurve = mutableListOf(initial)
        portfolioReturns.forEach { r -> equity *= (1 + r); equityCurve.add(equity) }

        // Win rate — batch AVG lookup
        val sellTrades = trades.filter { it.side == "SELL" }
        val avgBuyPriceMap: Map<Long, BigDecimal> = if (sellTrades.isNotEmpty()) {
            val sellStockIds = sellTrades.map { it.stockId }.distinct()
            jdbc.query(
                """SELECT stock_id, AVG(price) AS avg_price FROM paper_trades
                   WHERE user_id = ? AND side = 'BUY'
                   AND stock_id IN (${sellStockIds.joinToString(",") { "?" }})
                   GROUP BY stock_id""",
                { rs, _ -> rs.getLong("stock_id") to rs.getBigDecimal("avg_price") },
                userId, *sellStockIds.toTypedArray(),
            ).toMap()
        } else emptyMap()

        val wins    = sellTrades.count { t -> t.price > (avgBuyPriceMap[t.stockId] ?: t.price) }
        val winRate = if (sellTrades.isNotEmpty()) wins.toDouble() / sellTrades.size * 100 else 0.0

        val dailyReturnsList = dates.drop(1).zip(portfolioReturns) { d, r -> DailyReturn(d.toString(), r * 100) }
        val ddSeries = dates.zip(RiskCalculator.drawdownSeries(equityCurve)) { d, dd -> DrawdownPoint(d.toString(), dd) }

        return RiskMetricsResponse(
            sharpeRatio  = RiskCalculator.sharpe(portfolioReturns),
            beta         = RiskCalculator.beta(portfolioReturns, portfolioReturns),
            maxDrawdown  = RiskCalculator.maxDrawdown(equityCurve),
            volatility   = RiskCalculator.annualizedVolatility(portfolioReturns),
            var95        = RiskCalculator.var95(portfolioReturns),
            winRate      = winRate,
            totalTrades  = trades.size,
            avgReturn    = if (portfolioReturns.isNotEmpty()) portfolioReturns.average() * 100 else 0.0,
            dailyReturns = dailyReturnsList,
            drawdownSeries = ddSeries,
            hasEnoughData  = true,
            message        = null,
        )
    }
}
