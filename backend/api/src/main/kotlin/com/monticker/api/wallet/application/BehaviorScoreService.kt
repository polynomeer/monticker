package com.monticker.api.wallet.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.wallet.domain.BehaviorGrade
import com.monticker.api.wallet.domain.BehaviorScore
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.events.GradeChangedEvent
import com.monticker.api.wallet.infrastructure.BehaviorScoreRepository
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture

data class BehaviorScoreResponse(
    val userId: Long,
    val scoreDate: LocalDate,
    val behaviorScore: Int,
    val survivalScore: Int,
    val grade: BehaviorGrade,
    val feedback: List<String>,
    val reliabilityNotes: Map<String, Any?>,
)

@Service
@Transactional
class BehaviorScoreService(
    private val scoreRepo: BehaviorScoreRepository,
    private val emotionTagRepo: EmotionTagRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun recalculate(userId: Long, date: LocalDate): BehaviorScoreResponse =
        calculateAndSave(userId, date)

    /**
     * BehaviorScore를 비동기로 계산한다.
     * Batch Job / API 호출 모두 이 메서드를 통해 논블로킹으로 처리한다.
     *
     * @return CompletableFuture — 호출자는 결과가 필요할 때만 .get() 또는 .thenAccept()로 처리
     */
    @Async("behaviorScoreExecutor")
    fun recalculateAsync(userId: Long, date: LocalDate): CompletableFuture<BehaviorScoreResponse> =
        CompletableFuture.completedFuture(calculateAndSave(userId, date))

    /**
     * 여러 유저의 BehaviorScore를 병렬로 계산하고 모든 결과를 기다린다.
     * Batch Partitioner가 분할한 userId 목록을 받아 병렬 집계한다.
     */
    @Transactional
    fun recalculateBatchAsync(userIds: List<Long>, date: LocalDate): List<BehaviorScoreResponse> {
        val futures = userIds.map { recalculateAsync(it, date) }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { futures.map { it.join() } }
            .join()
    }

    fun getOrCalculateScore(userId: Long, date: LocalDate): BehaviorScoreResponse {
        val existing = scoreRepo.findByUserIdAndScoreDate(userId, date)
        if (existing.isPresent) {
            val s = existing.get()
            val breakdown = s.scoreBreakdown?.let {
                objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
            } ?: emptyMap()
            val feedback = s.feedbackJson?.let {
                objectMapper.readValue(it, List::class.java) as List<String>
            } ?: emptyList()
            return BehaviorScoreResponse(
                userId = userId,
                scoreDate = date,
                behaviorScore = s.behaviorScore ?: 70,
                survivalScore = s.survivalScore ?: 80,
                grade = s.grade ?: BehaviorGrade.fromScore(s.behaviorScore ?: 70),
                feedback = feedback,
                reliabilityNotes = breakdown,
            )
        }

        return calculateAndSave(userId, date)
    }

    internal fun calculateAndSave(userId: Long, date: LocalDate): BehaviorScoreResponse {
        val zone = ZoneId.of("Asia/Seoul")
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val hourAgo = Instant.now().minusSeconds(3600)

        // Today's trades
        val todayTrades = jdbc.queryForList(
            "SELECT id, stock_id, side, price, quantity, traded_at FROM paper_trades WHERE user_id = ? AND traded_at >= ? AND traded_at < ?",
            userId, dayStart, dayEnd
        )
        val todayTradeCount = todayTrades.size

        // Today's emotion tags with FOMO/ANXIOUS
        val todayTagIds = todayTrades.map { (it["id"] as Number).toLong() }
        val fomoCount = if (todayTagIds.isNotEmpty()) {
            emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId)
                .count { it.paperTradeId in todayTagIds && it.emotion == EmotionType.FOMO }
        } else 0
        val anxiousCount = if (todayTagIds.isNotEmpty()) {
            emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId)
                .count { it.paperTradeId in todayTagIds && it.emotion == EmotionType.ANXIOUS }
        } else 0

        // Chasing trades: stock was up >3% in prior hour
        var chasingCount = 0
        for (trade in todayTrades) {
            val stockId = (trade["stock_id"] as Number).toLong()
            val tradedAt = trade["traded_at"] as Instant
            val hourBeforeTrade = tradedAt.minusSeconds(3600)
            val priceChange = runCatching {
                val before = jdbc.queryForObject(
                    "SELECT close FROM candles_1m WHERE stock_id = ? AND candle_time <= ? ORDER BY candle_time DESC LIMIT 1",
                    BigDecimal::class.java, stockId, hourBeforeTrade
                )
                val after = jdbc.queryForObject(
                    "SELECT close FROM candles_1m WHERE stock_id = ? AND candle_time <= ? ORDER BY candle_time DESC LIMIT 1",
                    BigDecimal::class.java, stockId, tradedAt
                )
                if (before != null && after != null && before > BigDecimal.ZERO) {
                    after.subtract(before).divide(before, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal("100")).toDouble()
                } else null
            }.getOrNull()
            if (priceChange != null && priceChange > 3.0) chasingCount++
        }

        // Behavior score
        var behaviorScore = 70
        val behaviorFeedback = mutableListOf<String>()
        behaviorScore += 15 // not impulsive (always true in mock)

        if (fomoCount == 0 && anxiousCount == 0) {
            behaviorScore += 10
            behaviorFeedback.add("감정적 충동 거래 없음 (+10점)")
        }
        if (fomoCount > 0) {
            behaviorScore -= 15
            behaviorFeedback.add("FOMO 거래 ${fomoCount}건 감지 (-15점)")
        }
        if (todayTradeCount > 5) {
            behaviorScore -= 10
            behaviorFeedback.add("과다 거래 ${todayTradeCount}건 (-10점)")
        }
        if (chasingCount > 0) {
            behaviorScore -= 5 * chasingCount
            behaviorFeedback.add("가격 추격 매수 ${chasingCount}건 (-${5 * chasingCount}점)")
        }
        behaviorScore = behaviorScore.coerceIn(0, 100)

        // Survival score
        var survivalScore = 80
        val survivalFeedback = mutableListOf<String>()

        // Holdings concentration
        val holdings = jdbc.queryForList(
            """SELECT stock_id,
                      SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) AS net_qty
               FROM paper_trades WHERE user_id = ?
               GROUP BY stock_id
               HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
            userId
        )

        var totalHoldingsValue = BigDecimal.ZERO
        val holdingValues = holdings.mapNotNull { row ->
            val stockId = (row["stock_id"] as Number).toLong()
            val qty = (row["net_qty"] as Number).toInt()
            val price = runCatching {
                jdbc.queryForObject(
                    "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                    BigDecimal::class.java, stockId
                )
            }.getOrNull() ?: return@mapNotNull null
            val value = price.multiply(BigDecimal(qty))
            totalHoldingsValue = totalHoldingsValue.add(value)
            Pair(stockId, value)
        }

        val account = runCatching {
            jdbc.queryForObject("SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId)
        }.getOrNull() ?: BigDecimal.ZERO
        val totalAssets = account + totalHoldingsValue

        if (totalHoldingsValue > BigDecimal.ZERO) {
            for ((_, value) in holdingValues) {
                val pct = value.divide(totalHoldingsValue, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100")).toDouble()
                if (pct > 70) {
                    survivalScore -= 25
                    survivalFeedback.add("단일 종목 비중 ${String.format("%.1f", pct)}% 초과 (-25점)")
                    break
                } else if (pct > 50) {
                    survivalScore -= 15
                    survivalFeedback.add("단일 종목 비중 ${String.format("%.1f", pct)}% 초과 (-15점)")
                    break
                }
            }
        }

        if (totalAssets > BigDecimal.ZERO) {
            val cashRatio = account.divide(totalAssets, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal("100")).toDouble()
            if (cashRatio < 5.0) {
                survivalScore -= 20
                survivalFeedback.add("현금 비중 ${String.format("%.1f", cashRatio)}% — 안전 마진 부족 (-20점)")
            }
        }

        val recentTrades = jdbc.queryForList(
            "SELECT id FROM paper_trades WHERE user_id = ? AND traded_at >= ?",
            userId, hourAgo
        ).size
        if (recentTrades > 3) {
            survivalScore -= 10
            survivalFeedback.add("최근 1시간 거래 ${recentTrades}건 — 과도한 단기 거래 (-10점)")
        }

        survivalScore = survivalScore.coerceIn(0, 100)

        val allFeedback = behaviorFeedback + survivalFeedback
        val reliabilityNotes = mapOf(
            "todayTradeCount" to todayTradeCount,
            "fomoCount" to fomoCount,
            "anxiousCount" to anxiousCount,
            "chasingCount" to chasingCount,
            "recentHourTradeCount" to recentTrades,
            "totalHoldingsValue" to totalHoldingsValue,
            "availableCash" to account,
            "totalAssets" to totalAssets,
        )

        val previousGrade = scoreRepo.findByUserIdAndScoreDate(userId, date.minusDays(1))
            .map { it.grade }
            .orElse(null)
        val newGrade = BehaviorGrade.fromScore(behaviorScore)

        scoreRepo.save(
            BehaviorScore(
                userId = userId,
                scoreDate = date,
                behaviorScore = behaviorScore,
                survivalScore = survivalScore,
                scoreBreakdown = objectMapper.writeValueAsString(reliabilityNotes),
                feedbackJson = objectMapper.writeValueAsString(allFeedback),
                grade = newGrade,
            )
        )

        // EDA: 등급이 변경된 경우에만 이벤트 발행 → GradeChangedEventListener가 푸시 발송
        if (previousGrade != newGrade) {
            eventPublisher.publishEvent(
                GradeChangedEvent(
                    userId        = userId,
                    scoreDate     = date,
                    previousGrade = previousGrade,
                    newGrade      = newGrade,
                    behaviorScore = behaviorScore,
                )
            )
        }

        return BehaviorScoreResponse(
            userId = userId,
            scoreDate = date,
            behaviorScore = behaviorScore,
            survivalScore = survivalScore,
            grade = newGrade,
            feedback = allFeedback,
            reliabilityNotes = reliabilityNotes,
        )
    }
}
