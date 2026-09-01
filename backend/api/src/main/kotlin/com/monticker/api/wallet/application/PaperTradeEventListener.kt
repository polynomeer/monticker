package com.monticker.api.wallet.application

import com.monticker.api.paper.events.PaperSettlementCompletedEvent
import com.monticker.api.paper.events.PaperTradeExecutedEvent
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * paper 모듈의 페이퍼 트레이딩 이벤트를 구독해 원장(ledger)을 기록하는 리스너.
 * paper가 wallet.application.LedgerService를 직접 호출하면 wallet -> paper 조회 의존성과
 * 맞물려 모듈 순환 의존이 생기므로, [OrderFilledEventListener]와 동일하게 이벤트로 분리한다.
 */
@Component
class PaperTradeEventListener(
    private val ledgerService: LedgerService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun onPaperTradeExecuted(event: PaperTradeExecutedEvent) {
        log.info("[Wallet] PaperTradeExecutedEvent received: tradeId={} userId={} side={} amount={}",
            event.tradeId, event.userId, event.side, event.amount)

        if (event.side == "BUY") {
            ledgerService.recordBuy(event.userId, event.tradeId, event.stockId, event.amount, event.balanceAfter)
        } else {
            ledgerService.recordSell(event.userId, event.tradeId, event.stockId, event.amount, event.balanceAfter)
        }
    }

    @ApplicationModuleListener
    fun onPaperSettlementCompleted(event: PaperSettlementCompletedEvent) {
        log.info("[Wallet] PaperSettlementCompletedEvent received: settlementId={} userId={}",
            event.settlementId, event.userId)

        ledgerService.recordSettlementComplete(
            userId       = event.userId,
            settlementId = event.settlementId,
            stockId      = event.stockId,
            fee          = event.fee,
            tax          = event.tax,
            balanceAfter = event.balanceAfter,
        )
    }
}
