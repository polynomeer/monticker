package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.paper.application.PaperTradeSummary
import com.monticker.api.paper.application.RealizedPnl
import com.monticker.api.wallet.domain.EmotionTag
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ReplayServiceTest {

    private val ledgerService = mockk<LedgerService>()
    private val tradeQueryService = mockk<PaperTradeQueryService>()
    private val realizedPnlService = mockk<PaperRealizedPnlService>()
    private val emotionTagRepo = mockk<EmotionTagRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = ReplayService(ledgerService, tradeQueryService, realizedPnlService, emotionTagRepo, jdbc)

    private val date = LocalDate.of(2026, 6, 30)

    init {
        every { tradeQueryService.findOwnedByIds(any(), any()) } returns emptyList()
        every { realizedPnlService.forSells(any(), any()) } returns emptyMap()
        every { emotionTagRepo.findAllByUserIdAndPaperTradeIdIn(any(), any()) } returns emptyList()
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>(), *anyVararg()) } returns emptyList()
    }

    private fun ledgerEvent(
        eventType: String,
        amount: BigDecimal,
        paperTradeId: Long? = null,
        stockId: Long? = null,
    ) = LedgerEventDto(
        id = 1L, eventType = eventType, amount = amount, balanceAfter = null,
        paperTradeId = paperTradeId, stockId = stockId, description = null, createdAt = Instant.now(),
    )

    private fun trade(id: Long, stockId: Long, side: String, qty: Int, price: String, origin: String? = "MANUAL", ref: Long? = null) =
        PaperTradeSummary(id, 1L, stockId, side, qty, BigDecimal(price), BigDecimal(price).multiply(BigDecimal(qty)), origin = origin, originRef = ref)

    private fun tag(tradeId: Long, emotion: EmotionType, memo: String? = null) =
        EmotionTag(id = tradeId, paperTradeId = tradeId, userId = 1L, emotion = emotion, memo = memo)

    private fun symbols(vararg pairs: Pair<Long, String>) {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>(), *anyVararg()) } returns pairs.toList()
    }

    @Test
    fun `FILL events are mapped to type BUY and SETTLEMENT to SELL`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-100000")),
            ledgerEvent("SETTLEMENT", BigDecimal("100000")),
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events.map { it.type }).containsExactly("BUY", "SELL")
    }

    @Test
    fun `DEPOSIT and WITHDRAWAL pass through and unrecognised types are filtered out`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("DEPOSIT", BigDecimal("500000")),
            ledgerEvent("CASH_RESERVED", BigDecimal("-50000")),
            ledgerEvent("WITHDRAWAL", BigDecimal("-200000")),
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events.map { it.type }).containsExactly("DEPOSIT", "WITHDRAWAL")
        assertThat(result.events.all { it.planned == null }).isTrue()
    }

    @Test
    fun `trade rows carry symbol qty price tradeId emotion memo and origin from batch lookups`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-700000"), paperTradeId = 10L, stockId = 100L),
        )
        every { tradeQueryService.findOwnedByIds(1L, listOf(10L)) } returns listOf(trade(10L, 100L, "BUY", 10, "70000", "WATCH_RULE", 3L))
        every { emotionTagRepo.findAllByUserIdAndPaperTradeIdIn(1L, listOf(10L)) } returns listOf(tag(10L, EmotionType.IMPATIENT, "급했다"))
        symbols(100L to "005930")

        val event = service.getDailyReplay(1L, date).events.single()

        assertThat(event.stockSymbol).isEqualTo("005930")
        assertThat(event.qty).isEqualTo(10)
        assertThat(event.price).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(event.tradeId).isEqualTo(10L)
        assertThat(event.emotion).isEqualTo("IMPATIENT")
        assertThat(event.memo).isEqualTo("급했다")
        assertThat(event.origin).isEqualTo("WATCH_RULE")
        assertThat(event.originRef).isEqualTo(3L)
        assertThat(event.pnlPct).isNull()   // BUY는 손익이 없다
    }

    @Test
    fun `a ledger trade id that belongs to another stock is not treated as the trade`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-700000"), paperTradeId = 10L, stockId = 100L),
        )
        every { tradeQueryService.findOwnedByIds(1L, listOf(10L)) } returns listOf(trade(10L, 999L, "BUY", 1, "1"))

        val event = service.getDailyReplay(1L, date).events.single()

        assertThat(event.tradeId).isNull()
        assertThat(event.planned).isNull()
    }

    @Test
    fun `SELL rows take pnl from the realized pnl service and the summary totals it`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-500000"), paperTradeId = 10L, stockId = 200L),
            ledgerEvent("SETTLEMENT", BigDecimal("770000"), paperTradeId = 11L, stockId = 100L),
        )
        every { tradeQueryService.findOwnedByIds(1L, listOf(10L, 11L)) } returns listOf(
            trade(10L, 200L, "BUY", 10, "50000"),
            trade(11L, 100L, "SELL", 10, "77000"),
        )
        every { realizedPnlService.forSells(1L, listOf(11L)) } returns mapOf(11L to RealizedPnl(BigDecimal("70000"), BigDecimal("700000")))

        val result = service.getDailyReplay(1L, date)

        // 10주 × (77,000 − 70,000) = 70,000. 같은 날 다른 종목 매수는 손익이 아니다
        assertThat(result.summary.totalPnl).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(result.events[1].pnlPct!!).isCloseTo(10.0, within(0.01))
        assertThat(result.summary.bestTrade?.tradeId).isEqualTo(11L)
    }

    @Test
    fun `summary totalPnl is zero on a day with only buys — buying is not a loss`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-500000"), paperTradeId = 10L, stockId = 100L),
        )
        every { tradeQueryService.findOwnedByIds(1L, listOf(10L)) } returns listOf(trade(10L, 100L, "BUY", 10, "50000"))

        val result = service.getDailyReplay(1L, date)

        assertThat(result.summary.totalPnl).isEqualByComparingTo(BigDecimal.ZERO)
        verify(exactly = 1) { realizedPnlService.forSells(1L, emptyList()) }
    }

    // ADR-085 — 계획 준수율: 규칙·조건부·전략 출처이거나 PLANNED 태그면 계획된 주문, 출처를 모르면 집계에서 뺀다
    @Test
    fun `plan adherence counts rule conditional and PLANNED-tagged orders and skips unknown origins`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(
            ledgerEvent("FILL", BigDecimal("-1"), paperTradeId = 1L, stockId = 100L),   // WATCH_RULE → 계획
            ledgerEvent("FILL", BigDecimal("-1"), paperTradeId = 2L, stockId = 100L),   // MANUAL + PLANNED 태그 → 계획
            ledgerEvent("FILL", BigDecimal("-1"), paperTradeId = 3L, stockId = 100L),   // MANUAL → 계획 외
            ledgerEvent("SETTLEMENT", BigDecimal("1"), paperTradeId = 4L, stockId = 100L), // CONDITIONAL → 계획
            ledgerEvent("FILL", BigDecimal("-1"), paperTradeId = 5L, stockId = 100L),   // 출처 모름 → 제외
            ledgerEvent("DEPOSIT", BigDecimal("1")),                                    // 거래 아님 → 제외
        )
        every { tradeQueryService.findOwnedByIds(1L, any()) } returns listOf(
            trade(1L, 100L, "BUY", 1, "1", "WATCH_RULE", 7L),
            trade(2L, 100L, "BUY", 1, "1", "MANUAL"),
            trade(3L, 100L, "BUY", 1, "1", "MANUAL"),
            trade(4L, 100L, "SELL", 1, "1", "CONDITIONAL", 9L),
            trade(5L, 100L, "BUY", 1, "1", null),
        )
        every { emotionTagRepo.findAllByUserIdAndPaperTradeIdIn(1L, any()) } returns listOf(
            tag(2L, EmotionType.PLANNED),
            tag(3L, EmotionType.FOMO),
        )

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events.map { it.planned }).containsExactly(true, true, false, true, null, null)
        assertThat(result.summary.planEvaluatedCount).isEqualTo(4)
        assertThat(result.summary.unplannedCount).isEqualTo(1)
        assertThat(result.summary.planAdherencePct!!).isCloseTo(75.0, within(0.001))
    }

    @Test
    fun `plan adherence is null when no order on the day could be evaluated`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns listOf(ledgerEvent("DEPOSIT", BigDecimal("1")))

        val result = service.getDailyReplay(1L, date)

        assertThat(result.summary.planAdherencePct).isNull()
        assertThat(result.summary.unplannedCount).isZero()
    }

    @Test
    fun `isPlanned treats a PLANNED tag as planned even without a known origin`() {
        assertThat(ReplayService.isPlanned(null, "PLANNED")).isTrue()
        assertThat(ReplayService.isPlanned("STRATEGY", null)).isTrue()
        assertThat(ReplayService.isPlanned("MANUAL", "IMPATIENT")).isFalse()
        assertThat(ReplayService.isPlanned(null, null)).isNull()
    }

    @Test
    fun `returns an empty replay when there are no ledger events for the date`() {
        every { ledgerService.getLedgerForDate(1L, date) } returns emptyList()

        val result = service.getDailyReplay(1L, date)

        assertThat(result.events).isEmpty()
        assertThat(result.summary.tradeCount).isEqualTo(0)
        assertThat(result.summary.bestTrade).isNull()
        assertThat(result.summary.worstTrade).isNull()
    }
}
