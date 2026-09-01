package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.domain.SettlementCalculator
import com.monticker.api.paper.domain.SettlementStatus
import com.monticker.api.paper.events.PaperSettlementCompletedEvent
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Service
class PaperSettlementService(
    private val settlementRepo: PaperSettlementRepository,
    private val accountRepo: PaperAccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 페이퍼 트레이드 체결 직후 호출 — PENDING 정산 레코드를 T+2 영업일로 예약한다.
     * BUY: 즉시 debit된 금액에서 수수료가 추가로 차감된다는 사실을 기록.
     * SELL: 정산 완료 시까지 현금이 묶이는 교육적 흐름을 표현.
     */
    @Transactional
    fun createPending(trade: PaperTrade): PaperSettlement {
        val calc = SettlementCalculator.calculate(trade.side, trade.quantity, trade.price)
        val settleDate = BusinessDayCalculator.addBusinessDays(LocalDate.now(), 2)

        val settlement = PaperSettlement(
            tradeId     = trade.id,
            userId      = trade.userId,
            stockId     = trade.stockId,
            side        = trade.side,
            quantity    = trade.quantity,
            fillPrice   = trade.price,
            grossAmount = calc.grossAmount,
            fee         = calc.fee,
            tax         = calc.tax,
            netAmount   = calc.netAmount,
            settleDate  = settleDate,
        )
        return settlementRepo.save(settlement)
    }

    /**
     * 배치 Job에서 호출 — settle_date <= today인 PENDING 정산을 SETTLED로 전환하고
     * 수수료·세금을 반영해 잔고를 조정한다.
     */
    @Transactional
    fun settle(settlement: PaperSettlement) {
        val account = accountRepo.findByUserId(settlement.userId).orElse(null)
        if (account == null) {
            log.warn("정산 대상 계정 없음: userId={}, settlementId={}", settlement.userId, settlement.id)
            settlement.fail()
            settlementRepo.save(settlement)
            return
        }

        // 수수료·세금 차감 (BUY/SELL 모두 차감 방향)
        val deduction = settlement.fee.add(settlement.tax)
        if (deduction > BigDecimal.ZERO) {
            account.debit(com.monticker.api.common.domain.Money(deduction))
        }

        settlement.settle()
        settlementRepo.save(settlement)
        accountRepo.save(account)

        eventPublisher.publishEvent(
            PaperSettlementCompletedEvent(
                userId       = settlement.userId,
                settlementId = settlement.id,
                stockId      = settlement.stockId,
                fee          = settlement.fee,
                tax          = settlement.tax,
                balanceAfter = account.cash.amount,
            )
        )

        log.info(
            "정산 완료: id={} user={} side={} qty={} fee={} tax={}",
            settlement.id, settlement.userId, settlement.side,
            settlement.quantity, settlement.fee, settlement.tax,
        )
    }

    @Transactional(readOnly = true)
    fun getSettlements(userId: Long, pageable: Pageable): Page<PaperSettlement> =
        settlementRepo.findAllByUserIdOrderBySettleDateDesc(userId, pageable)

    @Transactional(readOnly = true)
    fun getPendingSettlements(userId: Long): List<PaperSettlement> =
        settlementRepo.findAllByUserIdAndStatus(userId, SettlementStatus.PENDING)

    @Transactional(readOnly = true)
    fun getByTradeId(tradeId: Long): PaperSettlement? =
        settlementRepo.findByTradeId(tradeId)
}
