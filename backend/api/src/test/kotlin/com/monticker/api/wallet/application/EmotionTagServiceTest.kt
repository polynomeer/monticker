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
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class EmotionTagServiceTest {

    private val emotionTagRepo = mockk<EmotionTagRepository>()
    private val tradeQueryService = mockk<PaperTradeQueryService>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = EmotionTagService(emotionTagRepo, tradeQueryService, jdbc)

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
    fun `getTag returns null when no tag exists for the trade`() {
        every { emotionTagRepo.findByPaperTradeId(404L) } returns null

        assertThat(service.getTag(404L)).isNull()
    }

    @Test
    fun `getTag maps an existing entity to its DTO`() {
        val tag = EmotionTag(id = 1L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.NEWS_BASED, memo = "기사 보고 매수")
        every { emotionTagRepo.findByPaperTradeId(1L) } returns tag

        val result = service.getTag(1L)

        assertThat(result).isNotNull()
        assertThat(result!!.emotion).isEqualTo("NEWS_BASED")
        assertThat(result.memo).isEqualTo("기사 보고 매수")
    }

    @Test
    fun `getAnalysis groups tags by emotion and counts them`() {
        val tags = listOf(
            EmotionTag(id = 1L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.FOMO),
            EmotionTag(id = 2L, paperTradeId = 2L, userId = 1L, emotion = EmotionType.FOMO),
            EmotionTag(id = 3L, paperTradeId = 3L, userId = 1L, emotion = EmotionType.LONG_TERM),
        )
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(1L) } returns tags
        every { tradeQueryService.findById(any()) } returns null

        val result = service.getAnalysis(1L)

        val fomoStat = result.stats.first { it.emotion == "FOMO" }
        val longTermStat = result.stats.first { it.emotion == "LONG_TERM" }
        assertThat(fomoStat.count).isEqualTo(2)
        assertThat(longTermStat.count).isEqualTo(1)
    }

    @Test
    fun `getAnalysis computes average return for emotions whose buy trades were later sold`() {
        val buyTrade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 10, price = BigDecimal("100"), amount = BigDecimal("1000"))
        val tag = EmotionTag(id = 1L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.CONFIDENT)
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(1L) } returns listOf(tag)
        every { tradeQueryService.findById(1L) } returns buyTrade
        every {
            jdbc.queryForObject(match<String> { it.contains("side = 'SELL'") }, BigDecimal::class.java, 1L, 100L, buyTrade.tradedAt)
        } returns BigDecimal("110")

        val result = service.getAnalysis(1L)

        val confidentStat = result.stats.first { it.emotion == "CONFIDENT" }
        // (110 - 100) / 100 * 100 = 10%
        assertThat(confidentStat.avgReturnPct).isNotNull()
        assertThat(confidentStat.avgReturnPct!!).isCloseTo(10.0, org.assertj.core.api.Assertions.within(0.0001))
    }

    @Test
    fun `getAnalysis leaves avgReturnPct null when the buy trade has not yet been sold`() {
        val buyTrade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "BUY", quantity = 10, price = BigDecimal("100"), amount = BigDecimal("1000"))
        val tag = EmotionTag(id = 1L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.CONFIDENT)
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(1L) } returns listOf(tag)
        every { tradeQueryService.findById(1L) } returns buyTrade
        every {
            jdbc.queryForObject(any<String>(), BigDecimal::class.java, any(), any(), any())
        } throws org.springframework.dao.EmptyResultDataAccessException(1)

        val result = service.getAnalysis(1L)

        val confidentStat = result.stats.first { it.emotion == "CONFIDENT" }
        assertThat(confidentStat.avgReturnPct).isNull()
    }

    @Test
    fun `getAnalysis ignores tags attached to SELL trades when computing returns`() {
        val sellTrade = PaperTradeSummary(id = 1L, userId = 1L, stockId = 100L, side = "SELL", quantity = 10, price = BigDecimal("100"), amount = BigDecimal("1000"))
        val tag = EmotionTag(id = 1L, paperTradeId = 1L, userId = 1L, emotion = EmotionType.REBALANCING)
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(1L) } returns listOf(tag)
        every { tradeQueryService.findById(1L) } returns sellTrade

        val result = service.getAnalysis(1L)

        val stat = result.stats.first { it.emotion == "REBALANCING" }
        assertThat(stat.avgReturnPct).isNull()
    }

    @Test
    fun `getAnalysis returns an empty stats list when the user has no tags`() {
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(1L) } returns emptyList()

        val result = service.getAnalysis(1L)

        assertThat(result.stats).isEmpty()
    }
}
