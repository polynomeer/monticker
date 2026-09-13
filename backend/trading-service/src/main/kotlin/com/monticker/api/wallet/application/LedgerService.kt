package com.monticker.api.wallet.application

import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import org.springframework.data.domain.PageRequest
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

    /** ADR-043 — 계좌 초기화. 변화량이 0이면 기록하지 않는다. */
    fun recordReset(userId: Long, previousCash: BigDecimal, newCash: BigDecimal) {
        val delta = newCash - previousCash
        if (delta.signum() == 0) return
        ledgerRepo.save(
            LedgerEvent(
                userId       = userId,
                eventType    = if (delta.signum() > 0) LedgerEventType.DEPOSIT else LedgerEventType.WITHDRAWAL,
                amount       = delta,
                balanceAfter = newCash,
                description  = "모의투자 계좌 초기화",
                metadataJson = """{"previousCash":$previousCash}""",
            )
        )
    }

    /** ADR-043 — 커서 페이징. limit+1건을 읽어 다음 페이지 유무를 정확히 판정한다. */
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

    /** 지갑 메인 화면용 최근 n건 — DB에서 n건만 읽는다. */
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
