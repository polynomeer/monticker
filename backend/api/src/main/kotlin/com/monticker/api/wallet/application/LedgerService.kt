package com.monticker.api.wallet.application

import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.modulith.NamedInterface
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

/** ADR-043 — 커서 페이지. nextCursor가 null이면 마지막 페이지다. */
data class LedgerPage(
    val items: List<LedgerEventDto>,
    val nextCursor: Long?,
)

/**
 * ADR-013/016: 원장 기록 진입점 — brokerage/subscription/settlement 정산 도메인에서 호출하는 공개 API.
 */
@NamedInterface("api")
@Service
@Transactional
class LedgerService(
    private val ledgerRepo: LedgerEventRepository,
) {
    companion object {
        const val DEFAULT_PAGE = 20
        const val MAX_PAGE = 50
    }

    fun recordBuy(userId: Long, tradeId: Long, stockId: Long, amount: BigDecimal, balanceAfter: BigDecimal) {
        // 멱등성: 아웃박스 at-least-once 재전달(커넥션 풀 고갈 등으로 리스너 tx 완료 표시 실패 시 5분 뒤 재시도)에
        // 원장이 중복 기록되던 버그(L-05에서 발견) — 이미 있으면 no-op. DB 유니크 인덱스(V46)가 경합 백스톱.
        if (ledgerRepo.existsByPaperTradeIdAndEventType(tradeId, LedgerEventType.FILL)) return
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
        if (ledgerRepo.existsByPaperTradeIdAndEventType(tradeId, LedgerEventType.SETTLEMENT)) return
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
        if (ledgerRepo.existsByDedupKey("SETTLE:$settlementId")) return   // 아웃박스 재전달 멱등
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
                dedupKey     = "SETTLE:$settlementId",
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

    /** 계좌 초기화 — 변화량이 0이면 기록하지 않는다 (초기 잔고 그대로였던 계좌). */
    fun recordReset(userId: Long, previousCash: BigDecimal, newCash: BigDecimal, eventId: String) {
        val delta = newCash - previousCash
        if (delta.signum() == 0) return
        if (ledgerRepo.existsByDedupKey("RESET:$eventId")) return   // 아웃박스 재전달 멱등 (초기화 이벤트별 고유 id)
        ledgerRepo.save(
            LedgerEvent(
                userId       = userId,
                eventType    = if (delta.signum() > 0) LedgerEventType.DEPOSIT else LedgerEventType.WITHDRAWAL,
                amount       = delta,
                balanceAfter = newCash,
                description  = "모의투자 계좌 초기화",
                metadataJson = """{"previousCash":$previousCash}""",
                dedupKey     = "RESET:$eventId",
            )
        )
    }

    /**
     * ADR-043 — 커서 페이징. limit은 [1, MAX_PAGE]로 clamp한다.
     * limit+1건을 읽어 다음 페이지 존재 여부를 정확히 판정한다 — "꽉 찬 마지막 페이지" 뒤에
     * 빈 페이지를 한 번 더 요청하게 만들지 않는다.
     */
    @Transactional(readOnly = true)
    fun getLedger(userId: Long, cursor: Long? = null, limit: Int = DEFAULT_PAGE): LedgerPage {
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = ledgerRepo.findPage(userId, cursor ?: Long.MAX_VALUE, PageRequest.of(0, size + 1))
        val page = rows.take(size)
        return LedgerPage(
            items = page.map { it.toDto() },
            nextCursor = if (rows.size > size) page.last().id else null,
        )
    }

    /** 지갑 메인 화면용 최근 n건 — DB에서 n건만 읽는다. 이전엔 전체를 읽고 take(10)했다. */
    @Transactional(readOnly = true)
    fun getRecentLedger(userId: Long, n: Int = 10): List<LedgerEventDto> =
        ledgerRepo.findPage(userId, Long.MAX_VALUE, PageRequest.of(0, n)).map { it.toDto() }

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
