package com.monticker.api.wallet.application

import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal

/**
 * matching 모듈 이벤트를 구독해 원장(ledger)을 기록하는 리스너.
 *
 * @TransactionalEventListener(AFTER_COMMIT): 체결 트랜잭션이 커밋된 후에만 실행.
 * 체결이 롤백되면 원장 기록도 일어나지 않는다.
 *
 * wallet 모듈이 matching 모듈을 직접 import하지 않아도 되도록
 * Spring Modulith 이벤트 채널(ApplicationEventPublisher)을 사용한다.
 */
@Component
class OrderFilledEventListener(
    private val ledgerRepo: LedgerEventRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderFilled(event: OrderFilledEvent) {
        log.info("[Wallet] OrderFilledEvent received: orderId={} userId={} side={} amount={}",
            event.orderId, event.userId, event.side, event.amount)

        val balanceAfter = queryBalance(event.userId)
        val eventType    = if (event.side == "BUY") LedgerEventType.FILL else LedgerEventType.SETTLEMENT
        val ledgerAmount = if (event.side == "BUY") event.amount.negate() else event.amount

        ledgerRepo.save(
            LedgerEvent(
                userId       = event.userId,
                eventType    = eventType,
                amount       = ledgerAmount,
                balanceAfter = balanceAfter,
                paperTradeId = event.fillId,
                stockId      = event.stockId,
                description  = if (event.side == "BUY") "매수 체결" else "매도 체결",
            )
        )
    }

    @ApplicationModuleListener
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderCancelled(event: OrderCancelledEvent) {
        log.info("[Wallet] OrderCancelledEvent received: orderId={} userId={} refund={}",
            event.orderId, event.userId, event.refundAmount)

        if (event.refundAmount <= BigDecimal.ZERO) return

        val balanceAfter = queryBalance(event.userId)
        ledgerRepo.save(
            LedgerEvent(
                userId       = event.userId,
                eventType    = LedgerEventType.DEPOSIT,
                amount       = event.refundAmount,
                balanceAfter = balanceAfter,
                description  = "주문 취소 환불 (orderId=${event.orderId})",
            )
        )
    }

    private fun queryBalance(userId: Long): BigDecimal =
        jdbc.queryForObject(
            "SELECT cash FROM paper_accounts WHERE user_id = ?",
            BigDecimal::class.java, userId,
        ) ?: BigDecimal.ZERO
}
