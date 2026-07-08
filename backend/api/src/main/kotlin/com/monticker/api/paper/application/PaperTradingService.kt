package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import com.monticker.api.wallet.application.LedgerService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
@Transactional
class PaperTradingService(
    private val accountRepo: PaperAccountRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
    private val ledgerService: LedgerService,
) {
    private fun getOrCreateAccount(userId: Long): PaperAccount =
        accountRepo.findByUserId(userId).orElseGet {
            accountRepo.save(PaperAccount(userId = userId))
        }

    private fun getCurrentPrice(stockId: Long): BigDecimal =
        jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId
        ) ?: throw IllegalStateException("현재가 조회 불가: stockId=$stockId")

    fun getPortfolio(userId: Long): PortfolioResponse {
        val account  = getOrCreateAccount(userId)
        val holdings = buildHoldings(userId)
        val evalValue = holdings.fold(BigDecimal.ZERO) { acc, h -> acc + h.value }
        val totalValue = account.cash + evalValue
        val invested   = holdings.fold(BigDecimal.ZERO) { acc, h -> acc + h.avgPrice.multiply(BigDecimal(h.quantity)) }
        val pnl        = evalValue - invested
        val pnlRate    = if (invested > BigDecimal.ZERO)
            pnl.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 0.0
        return PortfolioResponse(account.cash, totalValue, pnl, pnlRate, holdings)
    }

    private fun buildHoldings(userId: Long): List<HoldingResponse> {
        val rows = tradeRepo.findHoldings(userId)
        return rows.mapNotNull { row ->
            val stockId  = (row[0] as Number).toLong()
            val qty      = (row[1] as Number).toInt()
            val avgPrice = row[2] as? BigDecimal ?: return@mapNotNull null
            val cur = runCatching { getCurrentPrice(stockId) }.getOrNull() ?: return@mapNotNull null
            val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", stockId)
            val value = cur.multiply(BigDecimal(qty))
            val cost  = avgPrice.multiply(BigDecimal(qty))
            val pnl   = value - cost
            val pnlRate = if (cost > BigDecimal.ZERO)
                pnl.divide(cost, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 0.0
            HoldingResponse(
                stockId, info["symbol"] as String, info["name"] as String,
                qty, avgPrice, cur, value, pnl, pnlRate
            )
        }
    }

    fun buy(userId: Long, stockId: Long, quantity: Int): TradeResultResponse {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        val account = getOrCreateAccount(userId)
        val price   = getCurrentPrice(stockId)
        val amount  = price.multiply(BigDecimal(quantity))
        account.debit(amount)
        accountRepo.save(account)
        val trade = tradeRepo.save(PaperTrade(userId=userId, stockId=stockId, side="BUY", quantity=quantity, price=price, amount=amount))
        ledgerService.recordBuy(userId, trade.id, stockId, amount, account.cash)
        return TradeResultResponse("BUY", stockId, quantity, price, amount, account.cash, trade.id)
    }

    fun sell(userId: Long, stockId: Long, quantity: Int): TradeResultResponse {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        val holdings = buildHoldings(userId)
        val holding  = holdings.find { it.stockId == stockId }
            ?: throw IllegalStateException("보유 종목 없음: stockId=$stockId")
        require(holding.quantity >= quantity) { "보유 수량 부족: 보유 ${holding.quantity}, 요청 $quantity" }
        val account = getOrCreateAccount(userId)
        val price   = getCurrentPrice(stockId)
        val amount  = price.multiply(BigDecimal(quantity))
        account.credit(amount)
        accountRepo.save(account)
        val trade = tradeRepo.save(PaperTrade(userId=userId, stockId=stockId, side="SELL", quantity=quantity, price=price, amount=amount))
        ledgerService.recordSell(userId, trade.id, stockId, amount, account.cash)
        return TradeResultResponse("SELL", stockId, quantity, price, amount, account.cash, trade.id)
    }

    fun getHistory(userId: Long): List<TradeHistoryResponse> =
        tradeRepo.findTop20ByUserIdOrderByTradedAtDesc(userId).map {
            val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", it.stockId)
            TradeHistoryResponse(it.id, it.side, it.stockId, info["symbol"] as String,
                info["name"] as String, it.quantity, it.price, it.amount, it.tradedAt)
        }

    fun reset(userId: Long) {
        val account = getOrCreateAccount(userId)
        account.reset()
        accountRepo.save(account)
        jdbc.update("DELETE FROM paper_trades WHERE user_id = ?", userId)
    }
    @Transactional(readOnly = true)
    fun getRiskMetrics(userId: Long): RiskMetricsResponse {
        val account  = getOrCreateAccount(userId)
        val trades   = tradeRepo.findTop20ByUserIdOrderByTradedAtDesc(userId)
        val holdings = buildHoldings(userId)

        if (holdings.isEmpty() && trades.isEmpty()) {
            return RiskMetricsResponse(
                sharpeRatio = 0.0, beta = 1.0, maxDrawdown = 0.0,
                volatility = 0.0, var95 = 0.0,
                winRate = 0.0, totalTrades = 0, avgReturn = 0.0,
                dailyReturns = emptyList(), drawdownSeries = emptyList(),
                hasEnoughData = false,
                message = "거래 내역이 없습니다. 먼저 모의 투자를 시작해보세요."
            )
        }

        val stockIds = (holdings.map { it.stockId } + trades.map { it.stockId }).distinct()
        if (stockIds.isEmpty()) return RiskMetricsResponse(
            0.0, 1.0, 0.0, 0.0, 0.0, 0.0, trades.size, 0.0,
            emptyList(), emptyList(), false, "종목 데이터 없음"
        )

        val placeholders = stockIds.joinToString(",") { "?" }
        val candles = jdbc.query(
            """SELECT stock_id, DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d, close
               FROM candles_1d WHERE stock_id IN ($placeholders) ORDER BY d, stock_id""",
            { rs, _ -> Triple(rs.getLong("stock_id"), rs.getDate("d").toLocalDate(), rs.getBigDecimal("close").toDouble()) },
            *stockIds.toTypedArray()
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
                message = "수익률 계산 데이터 부족 (${dates.size}일, 최소 5일 필요)"
            )
        }

        // 동일가중 포트폴리오 일별 평균가격
        val portfolioPrices = dates.map { d -> priceMap[d]?.values?.average() ?: 0.0 }
        val portfolioReturns = portfolioPrices.zipWithNext { a, b -> if (a == 0.0) 0.0 else (b - a) / a }

        // 포트폴리오 가치 곡선
        val initial = account.cash.toDouble() + holdings.sumOf { it.value.toDouble() }.coerceAtLeast(account.cash.toDouble())
        var equity  = initial
        val equityCurve = mutableListOf(initial)
        portfolioReturns.forEach { r -> equity *= (1 + r); equityCurve.add(equity) }

        // 거래 승률
        val sellTrades = trades.filter { it.side == "SELL" }
        val wins = sellTrades.count { t ->
            val avgBuy = runCatching {
                jdbc.queryForObject(
                    "SELECT AVG(price) FROM paper_trades WHERE user_id=? AND stock_id=? AND side='BUY'",
                    BigDecimal::class.java, userId, t.stockId
                )
            }.getOrNull() ?: t.price
            t.price > avgBuy
        }
        val winRate = if (sellTrades.isNotEmpty()) wins.toDouble() / sellTrades.size * 100 else 0.0

        val dailyReturnsList = dates.drop(1).zip(portfolioReturns) { d, r -> DailyReturn(d.toString(), r * 100) }
        val ddSeries = dates.zip(RiskCalculator.drawdownSeries(equityCurve)) { d, dd -> DrawdownPoint(d.toString(), dd) }

        return RiskMetricsResponse(
            sharpeRatio     = RiskCalculator.sharpe(portfolioReturns),
            beta            = RiskCalculator.beta(portfolioReturns, portfolioReturns),
            maxDrawdown     = RiskCalculator.maxDrawdown(equityCurve),
            volatility      = RiskCalculator.annualizedVolatility(portfolioReturns),
            var95           = RiskCalculator.var95(portfolioReturns),
            winRate         = winRate,
            totalTrades     = trades.size,
            avgReturn       = if (portfolioReturns.isNotEmpty()) portfolioReturns.average() * 100 else 0.0,
            dailyReturns    = dailyReturnsList,
            drawdownSeries  = ddSeries,
            hasEnoughData   = true,
            message         = null,
        )
    }


}

data class PortfolioResponse(val cash: BigDecimal, val totalValue: BigDecimal, val totalPnl: BigDecimal, val totalPnlRate: Double, val holdings: List<HoldingResponse>)
data class HoldingResponse(val stockId: Long, val symbol: String, val name: String, val quantity: Int, val avgPrice: BigDecimal, val currentPrice: BigDecimal, val value: BigDecimal, val pnl: BigDecimal, val pnlRate: Double)
data class TradeResultResponse(val side: String, val stockId: Long, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val remainingCash: BigDecimal, val tradeId: Long = 0)
data class TradeHistoryResponse(val id: Long, val side: String, val stockId: Long, val symbol: String, val name: String, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val tradedAt: java.time.Instant)
