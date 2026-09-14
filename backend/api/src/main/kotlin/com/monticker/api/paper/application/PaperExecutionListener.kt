package com.monticker.api.paper.application

import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.events.PaperTradeExecutedEvent
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * ADR-047 — 매칭 엔진의 체결을 계좌의 실행 기록으로 옮긴다: paper_trades(fill_id 링크) · portfolio_positions ·
 * T+2 정산 · PaperTradeExecutedEvent(→ wallet 원장 FILL).
 *
 * **동기 @EventListener** — Modulith 비동기 리스너가 아니다. 사가 트랜잭션 안에서 실행되므로 체결과 계좌 기록이
 * 원자적이다: 여기서 실패하면 체결도 롤백된다(계좌 정합성은 fail-closed). 파사드(PaperTradingService)는 같은
 * 트랜잭션에서 fill_id로 이 행을 찾아 tradeId를 돌려준다.
 */
@Component
class PaperExecutionListener(
    private val tradeRepo: PaperTradeRepository,
    private val projection: PortfolioPositionProjection,
    private val settlementService: PaperSettlementService,
    private val jdbc: JdbcTemplate,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onOrderFilled(event: OrderFilledEvent) {
        if (tradeRepo.findByFillId(event.fillId) != null) return   // 재발행(Outbox 재전송)에도 멱등
        val trade = tradeRepo.save(PaperTrade(
            userId = event.userId, stockId = event.stockId, side = event.side,
            quantity = event.quantity, price = event.fillPrice, amount = event.amount,
            tradedAt = event.filledAt, fillId = event.fillId,
        ))
        if (event.side == "BUY") projection.onBuy(event.userId, event.stockId, event.quantity, event.amount)
        else projection.onSell(event.userId, event.stockId, event.quantity)
        settlementService.createPending(trade)

        // 사가가 cash를 JDBC로 이미 갱신했다 — 같은 트랜잭션이라 여기서 읽으면 체결 후 잔고다
        val balanceAfter = jdbc.query("SELECT cash FROM paper_accounts WHERE user_id = ?", { rs, _ -> rs.getBigDecimal("cash") }, event.userId)
            .firstOrNull() ?: BigDecimal.ZERO
        eventPublisher.publishEvent(PaperTradeExecutedEvent(event.userId, trade.id, event.stockId, event.side, event.amount, balanceAfter))
        log.debug("[Paper] execution recorded: tradeId={} fillId={} {} {}x{}", trade.id, event.fillId, event.side, event.quantity, event.fillPrice)
    }
}
