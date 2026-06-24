package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
@Transactional
class PaperTradingService(
    private val accountRepo: PaperAccountRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
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
        require(account.cash >= amount) { "잔고 부족: 필요 ${amount}, 보유 ${account.cash}" }
        account.cash -= amount
        account.updatedAt = Instant.now()
        accountRepo.save(account)
        tradeRepo.save(PaperTrade(userId=userId, stockId=stockId, side="BUY", quantity=quantity, price=price, amount=amount))
        return TradeResultResponse("BUY", stockId, quantity, price, amount, account.cash)
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
        account.cash += amount
        account.updatedAt = Instant.now()
        accountRepo.save(account)
        tradeRepo.save(PaperTrade(userId=userId, stockId=stockId, side="SELL", quantity=quantity, price=price, amount=amount))
        return TradeResultResponse("SELL", stockId, quantity, price, amount, account.cash)
    }

    fun getHistory(userId: Long): List<TradeHistoryResponse> =
        tradeRepo.findTop20ByUserIdOrderByTradedAtDesc(userId).map {
            val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", it.stockId)
            TradeHistoryResponse(it.id, it.side, it.stockId, info["symbol"] as String,
                info["name"] as String, it.quantity, it.price, it.amount, it.tradedAt)
        }

    fun reset(userId: Long) {
        val account = getOrCreateAccount(userId)
        account.cash = BigDecimal("10000000")
        account.updatedAt = Instant.now()
        accountRepo.save(account)
        jdbc.update("DELETE FROM paper_trades WHERE user_id = ?", userId)
    }
}

data class PortfolioResponse(val cash: BigDecimal, val totalValue: BigDecimal, val totalPnl: BigDecimal, val totalPnlRate: Double, val holdings: List<HoldingResponse>)
data class HoldingResponse(val stockId: Long, val symbol: String, val name: String, val quantity: Int, val avgPrice: BigDecimal, val currentPrice: BigDecimal, val value: BigDecimal, val pnl: BigDecimal, val pnlRate: Double)
data class TradeResultResponse(val side: String, val stockId: Long, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val remainingCash: BigDecimal)
data class TradeHistoryResponse(val id: Long, val side: String, val stockId: Long, val symbol: String, val name: String, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val tradedAt: java.time.Instant)
