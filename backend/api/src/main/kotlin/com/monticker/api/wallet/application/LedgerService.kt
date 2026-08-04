package com.monticker.api.wallet.application

import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LedgerEventDto(
    val id: Long,
    val eventType: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal?,
    val paperTradeId: Long?,
    val stockId: Long?,
    val description: String?,
    val createdAt: Instant,
)

@Service
@Transactional
class LedgerService(
    private val ledgerRepo: LedgerEventRepository,
) {

    fun recordBuy(userId: Long, tradeId: Long, stockId: Long, amount: BigDecimal, balanceAfter: BigDecimal) {
        ledgerRepo.save(
            LedgerEvent(
                userId = userId,
                eventType = LedgerEventType.FILL,
                amount = amount.negate(),
                balanceAfter = balanceAfter,
                paperTradeId = tradeId,
                stockId = stockId,
                description = "매수 체결",
            )
        )
    }

    fun recordSell(userId: Long, tradeId: Long, stockId: Long, amount: BigDecimal, balanceAfter: BigDecimal) {
        ledgerRepo.save(
            LedgerEvent(
                userId = userId,
                eventType = LedgerEventType.SETTLEMENT,
                amount = amount,
                balanceAfter = balanceAfter,
                paperTradeId = tradeId,
                stockId = stockId,
                description = "매도 체결",
            )
        )
    }

    fun recordSettlementComplete(
        userId: Long,
        settlementId: Long,
        stockId: Long,
        fee: BigDecimal,
        tax: BigDecimal,
        balanceAfter: BigDecimal,
    ) {
        val total = fee.add(tax)
        ledgerRepo.save(
            LedgerEvent(
                userId       = userId,
                eventType    = LedgerEventType.PAPER_SETTLEMENT_COMPLETE,
                amount       = total.negate(),
                balanceAfter = balanceAfter,
                stockId      = stockId,
                description  = "T+2 정산 완료 (수수료 $fee, 세금 $tax)",
                metadataJson = """{"settlementId":$settlementId,"fee":$fee,"tax":$tax}""",
            )
        )
    }

    fun recordSubscriptionPayment(userId: Long, planCode: String, amount: BigDecimal, paymentId: Long) {
        ledgerRepo.save(
            LedgerEvent(
                userId       = userId,
                eventType    = LedgerEventType.SUBSCRIPTION_PAYMENT,
                amount       = amount.negate(),
                description  = "구독료 결제 ($planCode)",
                metadataJson = """{"paymentId":$paymentId,"planCode":"$planCode"}""",
            )
        )
    }

    fun recordCreatorEarning(creatorId: Long, strategyId: Long, netAmount: BigDecimal, earningId: Long) {
        ledgerRepo.save(
            LedgerEvent(
                userId       = creatorId,
                eventType    = LedgerEventType.CREATOR_EARNING_CREDITED,
                amount       = netAmount,
                description  = "전략 수익 적립",
                metadataJson = """{"earningId":$earningId,"strategyId":$strategyId}""",
            )
        )
    }

    fun recordCreatorPayoutPaid(creatorId: Long, amount: BigDecimal, payoutId: Long) {
        ledgerRepo.save(
            LedgerEvent(
                userId       = creatorId,
                eventType    = LedgerEventType.CREATOR_PAYOUT_PAID,
                amount       = amount.negate(),
                description  = "전략 수익 출금",
                metadataJson = """{"payoutId":$payoutId}""",
            )
        )
    }

    fun recordBrokerageSettlement(
        userId: Long,
        settlementId: Long,
        symbol: String,
        side: String,
        netAmount: BigDecimal,
    ) {
        ledgerRepo.save(
            LedgerEvent(
                userId       = userId,
                eventType    = LedgerEventType.BROKERAGE_SETTLEMENT,
                amount       = if (side == "SELL") netAmount else netAmount.negate(),
                description  = "실거래 정산 ($symbol $side)",
                metadataJson = """{"settlementId":$settlementId,"symbol":"$symbol","side":"$side"}""",
            )
        )
    }

    fun recordDeposit(userId: Long, amount: BigDecimal, balanceAfter: BigDecimal) {
        ledgerRepo.save(
            LedgerEvent(
                userId = userId,
                eventType = LedgerEventType.DEPOSIT,
                amount = amount,
                balanceAfter = balanceAfter,
                description = "입금",
            )
        )
    }

    @Transactional(readOnly = true)
    fun getLedger(userId: Long): List<LedgerEventDto> =
        ledgerRepo.findAllByUserIdOrderByCreatedAtDesc(userId).map { it.toDto() }

    @Transactional(readOnly = true)
    fun getLedgerForDate(userId: Long, date: LocalDate): List<LedgerEventDto> {
        val zone = ZoneId.of("Asia/Seoul")
        val from = date.atStartOfDay(zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        return ledgerRepo.findAllByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId, from, to).map { it.toDto() }
    }

    private fun LedgerEvent.toDto() = LedgerEventDto(
        id = id,
        eventType = eventType.name,
        amount = amount,
        balanceAfter = balanceAfter,
        paperTradeId = paperTradeId,
        stockId = stockId,
        description = description,
        createdAt = createdAt,
    )
}
