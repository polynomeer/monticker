package com.monticker.api.paper.application

import com.monticker.api.common.domain.Price
import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.events.PaperTradeExecutedEvent
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
@Transactional
class PaperTradingService(
    private val accountRepo: PaperAccountRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
    private val eventPublisher: ApplicationEventPublisher,
    private val portfolioQueryService: PaperPortfolioQueryService,
    private val projection: PortfolioPositionProjection,
    private val settlementService: PaperSettlementService,
) {
    private fun getOrCreateAccount(userId: Long): PaperAccount =
        accountRepo.findByUserId(userId).orElseGet {
            accountRepo.save(PaperAccount(userId = userId))
        }

    private fun getCurrentPrice(stockId: Long): Price =
        jdbc.queryForObject(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId,
        )?.let { Price.of(it) } ?: throw IllegalStateException("현재가 조회 불가: stockId=$stockId")

    fun buy(userId: Long, stockId: Long, quantity: Int): TradeResultResponse {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        val account = getOrCreateAccount(userId)
        val price   = getCurrentPrice(stockId)
        val amount  = price.toMoney(quantity)
        account.debit(amount)
        accountRepo.save(account)
        val trade = tradeRepo.save(PaperTrade(userId = userId, stockId = stockId, side = "BUY",
            quantity = quantity, price = price.amount, amount = amount.amount))
        projection.onBuy(userId, stockId, quantity, amount.amount)
        eventPublisher.publishEvent(PaperTradeExecutedEvent(userId, trade.id, stockId, "BUY", amount.amount, account.cash.amount))
        settlementService.createPending(trade)
        return TradeResultResponse("BUY", stockId, quantity, price.amount, amount.amount, account.cash.amount, trade.id)
    }

    fun sell(userId: Long, stockId: Long, quantity: Int): TradeResultResponse {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        val holdings = portfolioQueryService.buildHoldings(userId)
        val holding  = holdings.find { it.stockId == stockId }
            ?: throw IllegalStateException("보유 종목 없음: stockId=$stockId")
        require(holding.quantity >= quantity) { "보유 수량 부족: 보유 ${holding.quantity}, 요청 $quantity" }
        val account = getOrCreateAccount(userId)
        val price   = getCurrentPrice(stockId)
        val amount  = price.toMoney(quantity)
        account.credit(amount)
        accountRepo.save(account)
        val trade = tradeRepo.save(PaperTrade(userId = userId, stockId = stockId, side = "SELL",
            quantity = quantity, price = price.amount, amount = amount.amount))
        projection.onSell(userId, stockId, quantity)
        eventPublisher.publishEvent(PaperTradeExecutedEvent(userId, trade.id, stockId, "SELL", amount.amount, account.cash.amount))
        settlementService.createPending(trade)
        return TradeResultResponse("SELL", stockId, quantity, price.amount, amount.amount, account.cash.amount, trade.id)
    }

    fun reset(userId: Long) {
        val account = getOrCreateAccount(userId)
        account.reset()
        accountRepo.save(account)
        jdbc.update("DELETE FROM paper_trades WHERE user_id = ?", userId)
        projection.onReset(userId)
    }
}

data class PortfolioResponse(val cash: BigDecimal, val totalValue: BigDecimal, val totalPnl: BigDecimal, val totalPnlRate: Double, val holdings: List<HoldingResponse>)
data class HoldingResponse(val stockId: Long, val symbol: String, val name: String, val quantity: Int, val avgPrice: BigDecimal, val currentPrice: BigDecimal, val value: BigDecimal, val pnl: BigDecimal, val pnlRate: Double)
data class TradeResultResponse(val side: String, val stockId: Long, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val remainingCash: BigDecimal, val tradeId: Long = 0)
data class TradeHistoryResponse(val id: Long, val side: String, val stockId: Long, val symbol: String, val name: String, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val tradedAt: java.time.Instant)
