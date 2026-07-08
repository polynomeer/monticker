package com.monticker.api.wallet.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.wallet.domain.BehaviorScore
import com.monticker.api.wallet.domain.EmotionTag
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.BehaviorScoreRepository
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class BehaviorScoreServiceTest {

    private val scoreRepo = mockk<BehaviorScoreRepository>()
    private val emotionTagRepo = mockk<EmotionTagRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = BehaviorScoreService(scoreRepo, emotionTagRepo, jdbc, objectMapper, eventPublisher)

    private val userId = 1L
    private val date: LocalDate = LocalDate.of(2026, 6, 30)

    @BeforeEach
    fun setUp() {
        every { scoreRepo.findByUserIdAndScoreDate(userId, any()) } returns Optional.empty()
        every { scoreRepo.save(any()) } answers { firstArg() }

        // No trades today by default
        every {
            jdbc.queryForList(match<String> { it.contains("FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any(), any())
        } returns emptyList()

        // No holdings by default
        every {
            jdbc.queryForList(match<String> { it.contains("GROUP BY stock_id") }, userId)
        } returns emptyList()

        // Healthy cash balance by default
        every {
            jdbc.queryForObject(match<String> { it.contains("paper_accounts") }, BigDecimal::class.java, userId)
        } returns BigDecimal("10000000")

        // No recent (last-hour) trades by default
        every {
            jdbc.queryForList(match<String> { it.contains("SELECT id FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any())
        } returns emptyList()
    }

    @Test
    fun `returns the cached score without recomputing when one already exists for the date`() {
        val cached = BehaviorScore(
            userId = userId, scoreDate = date, behaviorScore = 88, survivalScore = 77,
            scoreBreakdown = """{"todayTradeCount":2}""", feedbackJson = """["좋은 습관입니다"]""",
        )
        every { scoreRepo.findByUserIdAndScoreDate(userId, date) } returns Optional.of(cached)

        val result = service.getOrCalculateScore(userId, date)

        assertThat(result.behaviorScore).isEqualTo(88)
        assertThat(result.survivalScore).isEqualTo(77)
        assertThat(result.feedback).containsExactly("좋은 습관입니다")
    }

    @Test
    fun `falls back to default scores when cached row has null score fields`() {
        val cached = BehaviorScore(userId = userId, scoreDate = date, behaviorScore = null, survivalScore = null)
        every { scoreRepo.findByUserIdAndScoreDate(userId, date) } returns Optional.of(cached)

        val result = service.getOrCalculateScore(userId, date)

        assertThat(result.behaviorScore).isEqualTo(70)
        assertThat(result.survivalScore).isEqualTo(80)
    }

    @Test
    fun `calculates a high behavior score when there are no trades or emotional tags today`() {
        val result = service.getOrCalculateScore(userId, date)

        // base 70 + 15 (not impulsive) + 10 (no FOMO/anxious) = 95
        assertThat(result.behaviorScore).isEqualTo(95)
        assertThat(result.feedback).anyMatch { it.contains("감정적 충동 거래 없음") }
    }

    @Test
    fun `penalises behavior score for FOMO-tagged trades`() {
        val tradedAt = Instant.now()
        every {
            jdbc.queryForList(match<String> { it.contains("FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any(), any())
        } returns listOf(
            mapOf("id" to 10L, "stock_id" to 100L, "side" to "BUY", "price" to BigDecimal("1000"), "quantity" to 5, "traded_at" to tradedAt)
        )
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId) } returns listOf(
            EmotionTag(id = 1L, paperTradeId = 10L, userId = userId, emotion = EmotionType.FOMO)
        )
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L, any())
        } throws EmptyResultDataAccessException(1)

        val result = service.getOrCalculateScore(userId, date)

        // 70 + 15 (not impulsive) - 15 (fomo) = 70 ; no "no fomo/anxious" bonus applies
        assertThat(result.behaviorScore).isEqualTo(70)
        assertThat(result.feedback).anyMatch { it.contains("FOMO 거래 1건") }
    }

    @Test
    fun `penalises behavior score for trading more than 5 times in a day`() {
        val tradedAt = Instant.now()
        val sixTrades = (1..6).map { i ->
            mapOf("id" to i.toLong(), "stock_id" to 100L, "side" to "BUY", "price" to BigDecimal("1000"), "quantity" to 1, "traded_at" to tradedAt)
        }
        every {
            jdbc.queryForList(match<String> { it.contains("FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any(), any())
        } returns sixTrades
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId) } returns emptyList()
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L, any())
        } throws EmptyResultDataAccessException(1)

        val result = service.getOrCalculateScore(userId, date)

        // 70 + 15 (not impulsive) + 10 (no fomo/anxious) - 10 (6 trades > 5) = 85
        assertThat(result.behaviorScore).isEqualTo(85)
        assertThat(result.feedback).anyMatch { it.contains("과다 거래 6건") }
    }

    @Test
    fun `penalises behavior score 5 points per chasing trade where price rose over 3 percent in the prior hour`() {
        val tradedAt = Instant.now()
        every {
            jdbc.queryForList(match<String> { it.contains("FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any(), any())
        } returns listOf(
            mapOf("id" to 10L, "stock_id" to 100L, "side" to "BUY", "price" to BigDecimal("1050"), "quantity" to 5, "traded_at" to tradedAt)
        )
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId) } returns emptyList()
        // before price 1000, after price 1050 -> +5% > 3% threshold
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L, any())
        } returnsMany listOf(BigDecimal("1000"), BigDecimal("1050"))

        val result = service.getOrCalculateScore(userId, date)

        // 70 + 15 + 10 - 5 (1 chasing trade) = 90
        assertThat(result.behaviorScore).isEqualTo(90)
        assertThat(result.feedback).anyMatch { it.contains("가격 추격 매수 1건") }
    }

    @Test
    fun `behavior score never drops below zero`() {
        val tradedAt = Instant.now()
        val manyTrades = (1..20).map { i ->
            mapOf("id" to i.toLong(), "stock_id" to 100L, "side" to "BUY", "price" to BigDecimal("1050"), "quantity" to 1, "traded_at" to tradedAt)
        }
        every {
            jdbc.queryForList(match<String> { it.contains("FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any(), any())
        } returns manyTrades
        every { emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId) } returns
            manyTrades.map { EmotionTag(id = (it["id"] as Long), paperTradeId = it["id"] as Long, userId = userId, emotion = EmotionType.FOMO) }
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L, any())
        } returnsMany List(40) { if (it % 2 == 0) BigDecimal("1000") else BigDecimal("1050") }

        val result = service.getOrCalculateScore(userId, date)

        assertThat(result.behaviorScore).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `penalises survival score 25 points when a single stock exceeds 70 percent concentration`() {
        every {
            jdbc.queryForList(match<String> { it.contains("GROUP BY stock_id") }, userId)
        } returns listOf(mapOf("stock_id" to 100L, "net_qty" to 100))
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L)
        } returns BigDecimal("90000") // 100 * 90000 = 9,000,000 dominates total assets
        every {
            jdbc.queryForObject(match<String> { it.contains("paper_accounts") }, BigDecimal::class.java, userId)
        } returns BigDecimal("1000000")

        val result = service.getOrCalculateScore(userId, date)

        // total assets = 1,000,000 + 9,000,000 = 10,000,000 ; concentration = 90% > 70%
        assertThat(result.survivalScore).isLessThanOrEqualTo(80 - 25)
        assertThat(result.feedback).anyMatch { it.contains("단일 종목 비중") && it.contains("초과") }
    }

    @Test
    fun `penalises survival score 20 points when cash ratio falls below 5 percent`() {
        every {
            jdbc.queryForList(match<String> { it.contains("GROUP BY stock_id") }, userId)
        } returns listOf(mapOf("stock_id" to 100L, "net_qty" to 100))
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L)
        } returns BigDecimal("9900") // holdings value 990,000
        every {
            jdbc.queryForObject(match<String> { it.contains("paper_accounts") }, BigDecimal::class.java, userId)
        } returns BigDecimal("10000") // cash ratio = 10000 / 1,000,000 = 1%

        val result = service.getOrCalculateScore(userId, date)

        assertThat(result.feedback).anyMatch { it.contains("현금 비중") && it.contains("안전 마진 부족") }
    }

    @Test
    fun `penalises survival score 10 points for more than 3 trades in the last hour`() {
        every {
            jdbc.queryForList(match<String> { it.contains("SELECT id FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any())
        } returns listOf(mapOf("id" to 1L), mapOf("id" to 2L), mapOf("id" to 3L), mapOf("id" to 4L))

        val result = service.getOrCalculateScore(userId, date)

        // 80 - 10 = 70 (no other survival penalties apply: no holdings, full cash)
        assertThat(result.survivalScore).isEqualTo(70)
        assertThat(result.feedback).anyMatch { it.contains("최근 1시간 거래 4건") }
    }

    @Test
    fun `survival score never drops below zero`() {
        every {
            jdbc.queryForList(match<String> { it.contains("GROUP BY stock_id") }, userId)
        } returns listOf(mapOf("stock_id" to 100L, "net_qty" to 1000))
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, 100L)
        } returns BigDecimal("100000")
        every {
            jdbc.queryForObject(match<String> { it.contains("paper_accounts") }, BigDecimal::class.java, userId)
        } returns BigDecimal("1")
        every {
            jdbc.queryForList(match<String> { it.contains("SELECT id FROM paper_trades WHERE user_id = ? AND traded_at >=") }, userId, any())
        } returns listOf(mapOf("id" to 1L), mapOf("id" to 2L), mapOf("id" to 3L), mapOf("id" to 4L))

        val result = service.getOrCalculateScore(userId, date)

        assertThat(result.survivalScore).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `persists the computed score and feedback for future cache hits`() {
        val slot = slot<BehaviorScore>()
        every { scoreRepo.save(capture(slot)) } answers { slot.captured }

        service.getOrCalculateScore(userId, date)

        assertThat(slot.captured.userId).isEqualTo(userId)
        assertThat(slot.captured.scoreDate).isEqualTo(date)
        assertThat(slot.captured.behaviorScore).isEqualTo(95)
        assertThat(slot.captured.scoreBreakdown).isNotNull()
        assertThat(slot.captured.feedbackJson).isNotNull()
    }
}
