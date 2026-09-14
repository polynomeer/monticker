package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.paper.events.PaperAccountResetEvent
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
    private val projection: PortfolioPositionProjection,
    private val orderSubmitter: OrderSubmitter,
) {
    private fun getOrCreateAccount(userId: Long): PaperAccount =
        accountRepo.findByUserId(userId).orElseGet {
            accountRepo.save(PaperAccount(userId = userId))
        }

    /**
     * ADR-047 — 파사드. 현재가·잔고·수량 확인·기록을 직접 하지 않고 매칭 엔진에 MARKET 주문을 제출한다.
     * 리스크 게이트(@RiskChecked)가 이 경로에도 걸린다. 계좌 기록(paper_trades·포지션·정산·원장)은
     * PaperExecutionListener가 사가 트랜잭션 안에서 만든다 — 응답의 tradeId는 그 행이다.
     */
    fun buy(userId: Long, stockId: Long, quantity: Int): TradeResultResponse = execute(userId, stockId, "BUY", quantity)

    fun sell(userId: Long, stockId: Long, quantity: Int): TradeResultResponse = execute(userId, stockId, "SELL", quantity)

    private fun execute(userId: Long, stockId: Long, side: String, quantity: Int): TradeResultResponse {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        getOrCreateAccount(userId)
        val result = orderSubmitter.submitMarket(userId, stockId, side, quantity)
        val trade = tradeRepo.findByFillId(result.fillId)
            ?: throw IllegalStateException("체결 기록이 없습니다: fillId=${result.fillId}")   // 리스너가 같은 트랜잭션에 만든다
        // 사가가 cash를 JDBC로 바꿨다 — 같은 트랜잭션의 JPA 1차 캐시 엔티티는 갱신 전 값이라 JDBC로 읽는다
        val cash = jdbc.query("SELECT cash FROM paper_accounts WHERE user_id = ?", { rs, _ -> rs.getBigDecimal("cash") }, userId)
            .firstOrNull() ?: BigDecimal.ZERO
        return TradeResultResponse(side, stockId, quantity, result.fillPrice, result.amount, cash, trade.id)
    }

    fun reset(userId: Long) {
        // ADR-043 라이브 검증에서 발견: 미체결 BUY 주문의 예약금은 cash에서 이미 빠져 있다. 그 상태로 잔고를
        // 1,000만으로 되돌리면 나중에 취소될 때 환불이 1,000만 위에 얹혀 돈이 생긴다. 초기화 뒤 리스너로 취소해도
        // 순서가 같으므로(초기화 → 환불) 답이 아니다 — 먼저 취소하게 한다.
        val openOrders = jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE user_id = ? AND status IN ('PENDING', 'PARTIALLY_FILLED')",
            Long::class.java, userId,
        ) ?: 0L
        check(openOrders == 0L) { "미체결 주문 ${openOrders}건이 있어 초기화 불가 — 먼저 취소하세요" }

        val account = getOrCreateAccount(userId)
        val previousCash = account.cash.amount
        account.reset()
        accountRepo.save(account)
        // 원장은 append-only(ADR-013) — 거래 행은 지워도 초기화 자체는 원장에 남긴다 (ADR-043 대사 불변식)
        eventPublisher.publishEvent(PaperAccountResetEvent(userId, previousCash, account.cash.amount))
        jdbc.update("DELETE FROM paper_trades WHERE user_id = ?", userId)
        projection.onReset(userId)
    }
}

data class PortfolioResponse(val cash: BigDecimal, val totalValue: BigDecimal, val totalPnl: BigDecimal, val totalPnlRate: Double, val holdings: List<HoldingResponse>)
data class HoldingResponse(val stockId: Long, val symbol: String, val name: String, val quantity: Int, val avgPrice: BigDecimal, val currentPrice: BigDecimal, val value: BigDecimal, val pnl: BigDecimal, val pnlRate: Double)
data class TradeResultResponse(val side: String, val stockId: Long, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val remainingCash: BigDecimal, val tradeId: Long = 0)
data class TradeHistoryResponse(val id: Long, val side: String, val stockId: Long, val symbol: String, val name: String, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val tradedAt: java.time.Instant)
