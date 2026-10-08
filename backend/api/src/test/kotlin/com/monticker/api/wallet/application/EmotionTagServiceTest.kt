package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.paper.application.PaperTradeSummary
import com.monticker.api.wallet.domain.EmotionTag
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class EmotionTagServiceTest {

    private val emotionTagRepo = mockk<EmotionTagRepository>()
    private val tradeQueryService = mockk<PaperTradeQueryService>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = EmotionTagService(emotionTagRepo, tradeQueryService, jdbc)

    private fun trade(id: Long, ownerId: Long) = PaperTradeSummary(
        id = id, userId = ownerId, stockId = 100L, side = "BUY",
        quantity = 10, price = BigDecimal("100"), amount = BigDecimal("1000"),
    )

    @BeforeEach
    fun stubOwnedTrade() {
        // 기본: trade 1은 user 1 소유
        every { tradeQueryService.findById(1L) } returns trade(id = 1L, ownerId = 1L)
    }

    @Test
    fun `saveTag persists a new emotion tag for a trade with no existing tag`() {
        every { emotionTagRepo.findByPaperTradeId(1L) } returns null
        val slot = slot<EmotionTag>()
        every { emotionTagRepo.save(capture(slot)) } answers { slot.captured }

        val result = service.saveTag(userId = 1L, tradeId = 1L, emotion = "fomo", memo = "급등 놓칠까봐")

        assertThat(slot.captured.emotion).isEqualTo(EmotionType.FOMO)
        assertThat(result.emotion).isEqualTo("FOMO")
        assertThat(result.memo).isEqualTo("급등 놓칠까봐")
    }

    // ADR-085 — "계획대로"·"조급함"이 OTHER + 메모가 아니라 고유 값으로 저장된다
    @Test
    fun `saveTag accepts the PLANNED and IMPATIENT emotions`() {
        every { emotionTagRepo.findByPaperTradeId(1L) } returns null
        every { emotionTagRepo.save(any()) } answers { firstArg() }

        assertThat(service.saveTag(userId = 1L, tradeId = 1L, emotion = "PLANNED", memo = null).emotion).isEqualTo("PLANNED")
        assertThat(service.saveTag(userId = 1L, tradeId = 1L, emotion = "impatient", memo = null).emotion).isEqualTo("IMPATIENT")
    }

    @Test
    fun `saveTag is case-insensitive for the emotion value`() {
        every { emotionTagRepo.findByPaperTradeId(1L) } returns null
        every { emotionTagRepo.save(any()) } answers { firstArg() }

        val result = service.saveTag(userId = 1L, tradeId = 1L, emotion = "Confident", memo = null)

        assertThat(result.emotion).isEqualTo("CONFIDENT")
    }

    @Test
    fun `saveTag throws for an unrecognised emotion value`() {
        every { emotionTagRepo.findByPaperTradeId(1L) } returns null

        org.assertj.core.api.Assertions.assertThatThrownBy {
            service.saveTag(userId = 1L, tradeId = 1L, emotion = "EXCITEMENT", memo = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `saveTag replaces an existing tag for the same trade (delete then insert)`() {
        val existing = EmotionTag(id = 5L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.ANXIOUS)
        every { emotionTagRepo.findByPaperTradeId(1L) } returns existing
        every { emotionTagRepo.delete(existing) } returns Unit
        every { emotionTagRepo.save(any()) } answers { firstArg() }

        service.saveTag(userId = 1L, tradeId = 1L, emotion = "LONG_TERM", memo = null)

        verify { emotionTagRepo.delete(existing) }
        verify { emotionTagRepo.save(any()) }
    }

    @Test
    fun `getTag returns null when no tag exists for the caller's trade`() {
        every { emotionTagRepo.findByPaperTradeId(1L) } returns null

        assertThat(service.getTag(userId = 1L, tradeId = 1L)).isNull()
    }

    @Test
    fun `getTag maps an existing entity to its DTO`() {
        val tag = EmotionTag(id = 1L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.NEWS_BASED, memo = "기사 보고 매수")
        every { emotionTagRepo.findByPaperTradeId(1L) } returns tag

        val result = service.getTag(userId = 1L, tradeId = 1L)

        assertThat(result).isNotNull()
        assertThat(result!!.emotion).isEqualTo("NEWS_BASED")
        assertThat(result.memo).isEqualTo("기사 보고 매수")
    }

    // ── 소유권 (IDOR) ────────────────────────────────────────────────

    @Test
    fun `getTag throws not-found when the trade belongs to another user`() {
        every { tradeQueryService.findById(2L) } returns trade(id = 2L, ownerId = 99L)
        every { emotionTagRepo.findByPaperTradeId(2L) } returns
            EmotionTag(id = 7L, paperTradeId = 2L, userId = 99L, emotion = EmotionType.FOMO, memo = "남의 메모")

        assertThatThrownBy { service.getTag(userId = 1L, tradeId = 2L) }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessage("Paper trade not found: 2")
        verify(exactly = 0) { emotionTagRepo.findByPaperTradeId(any()) }
    }

    @Test
    fun `getTag gives the same not-found for a missing trade as for another user's trade`() {
        every { tradeQueryService.findById(404L) } returns null

        assertThatThrownBy { service.getTag(userId = 1L, tradeId = 404L) }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessage("Paper trade not found: 404")
    }

    @Test
    fun `getTag hides a tag on the caller's trade that was written by another user`() {
        every { emotionTagRepo.findByPaperTradeId(1L) } returns
            EmotionTag(id = 7L, paperTradeId = 1L, userId = 99L, emotion = EmotionType.FOMO, memo = "심어둔 메모")

        assertThat(service.getTag(userId = 1L, tradeId = 1L)).isNull()
    }

    @Test
    fun `saveTag on another user's trade throws not-found and neither deletes nor saves`() {
        every { tradeQueryService.findById(2L) } returns trade(id = 2L, ownerId = 99L)

        assertThatThrownBy { service.saveTag(userId = 1L, tradeId = 2L, emotion = "FOMO", memo = "덮어쓰기") }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessage("Paper trade not found: 2")
        verify(exactly = 0) { emotionTagRepo.findByPaperTradeId(any()) }
        verify(exactly = 0) { emotionTagRepo.delete(any()) }
        verify(exactly = 0) { emotionTagRepo.save(any()) }
    }

    @Test
    fun `saveTag on a missing trade throws not-found`() {
        every { tradeQueryService.findById(404L) } returns null

        assertThatThrownBy { service.saveTag(userId = 1L, tradeId = 404L, emotion = "FOMO", memo = null) }
            .isInstanceOf(NoSuchElementException::class.java)
        verify(exactly = 0) { emotionTagRepo.save(any()) }
    }

    // ── ADR-091 — 분석 집계는 순수 함수(EmotionAnalysis.aggregate), SQL은 EmotionAnalysisIntegrationTest ──

    private fun row(emotion: String, side: String = "BUY", price: String = "100", nextSell: String? = null) =
        EmotionRow(emotion, side, BigDecimal(price), nextSell?.let(::BigDecimal))

    @Test
    fun `aggregate groups tags by emotion, counts them and gives each a share of the total`() {
        val result = EmotionAnalysis.aggregate(listOf(row("FOMO"), row("FOMO"), row("LONG_TERM"), row("PLANNED")))

        assertThat(result.totalCount).isEqualTo(4)
        assertThat(result.stats.map { it.emotion }).containsExactly("FOMO", "LONG_TERM", "PLANNED") // 건수 내림차순, 같으면 이름순
        assertThat(result.stats.first().count).isEqualTo(2)
        assertThat(result.stats.first().sharePct).isEqualTo(50.0)
        assertThat(result.stats.sumOf { it.sharePct!! }).isCloseTo(100.0, org.assertj.core.api.Assertions.within(1e-9))
    }

    @Test
    fun `aggregate averages the return of buy tags that were later sold`() {
        val result = EmotionAnalysis.aggregate(listOf(row("CONFIDENT", nextSell = "110"), row("CONFIDENT", nextSell = "90")))
        // (+10% + −10%) / 2 = 0
        assertThat(result.stats.single().avgReturnPct!!).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1e-9))
        assertThat(EmotionAnalysis.aggregate(listOf(row("CONFIDENT", nextSell = "110"))).stats.single().avgReturnPct!!)
            .isCloseTo(10.0, org.assertj.core.api.Assertions.within(1e-9))
    }

    @Test
    fun `aggregate leaves avgReturnPct null for unsold buys and for sell tags`() {
        val result = EmotionAnalysis.aggregate(listOf(row("CONFIDENT"), row("REBALANCING", side = "SELL", nextSell = "120")))
        assertThat(result.stats.first { it.emotion == "CONFIDENT" }.avgReturnPct).isNull()
        assertThat(result.stats.first { it.emotion == "REBALANCING" }.avgReturnPct).isNull()
        assertThat(result.stats.first { it.emotion == "REBALANCING" }.count).isEqualTo(1)
    }

    @Test
    fun `aggregate of no tags is empty with a zero total and echoes the period`() {
        val period = com.monticker.api.common.time.KstPeriod(java.time.LocalDate.of(2026, 10, 5), java.time.LocalDate.of(2026, 10, 11))
        val result = EmotionAnalysis.aggregate(emptyList(), period)
        assertThat(result.stats).isEmpty()
        assertThat(result.totalCount).isZero()
        assertThat(result.from).isEqualTo(period.from)
        assertThat(result.to).isEqualTo(period.to)
    }
}
