package com.monticker.api.wallet.application

import com.monticker.api.paper.infrastructure.PaperTradeRepository
import com.monticker.api.wallet.domain.EmotionTag
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class EmotionTagDto(
    val id: Long,
    val paperTradeId: Long,
    val userId: Long,
    val emotion: String,
    val memo: String?,
    val createdAt: Instant,
)

data class EmotionStat(
    val emotion: String,
    val count: Int,
    val avgReturnPct: Double?,
)

data class EmotionAnalysisResponse(
    val stats: List<EmotionStat>,
)

@Service
@Transactional
class EmotionTagService(
    private val emotionTagRepo: EmotionTagRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
) {

    fun saveTag(userId: Long, tradeId: Long, emotion: String, memo: String?): EmotionTagDto {
        val existing = emotionTagRepo.findByPaperTradeId(tradeId)
        if (existing != null) {
            emotionTagRepo.delete(existing)
        }
        val emotionType = EmotionType.valueOf(emotion.uppercase())
        val tag = emotionTagRepo.save(
            EmotionTag(
                paperTradeId = tradeId,
                userId = userId,
                emotion = emotionType,
                memo = memo,
            )
        )
        return tag.toDto()
    }

    @Transactional(readOnly = true)
    fun getTag(tradeId: Long): EmotionTagDto? =
        emotionTagRepo.findByPaperTradeId(tradeId)?.toDto()

    @Transactional(readOnly = true)
    fun getAnalysis(userId: Long): EmotionAnalysisResponse {
        val tags = emotionTagRepo.findAllByUserIdOrderByCreatedAtDesc(userId)
        val grouped = tags.groupBy { it.emotion }

        val stats = grouped.map { (emotion, emotionTags) ->
            val returns = emotionTags.mapNotNull { tag ->
                val trade = tradeRepo.findById(tag.paperTradeId).orElse(null) ?: return@mapNotNull null
                if (trade.side != "BUY") return@mapNotNull null

                val sellPrice = runCatching {
                    jdbc.queryForObject(
                        """SELECT price FROM paper_trades
                           WHERE user_id = ? AND stock_id = ? AND side = 'SELL'
                             AND traded_at > ?
                           ORDER BY traded_at ASC LIMIT 1""",
                        BigDecimal::class.java,
                        userId, trade.stockId, trade.tradedAt
                    )
                }.getOrNull() ?: return@mapNotNull null

                val returnPct = sellPrice.subtract(trade.price)
                    .divide(trade.price, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
                    .toDouble()
                returnPct
            }

            EmotionStat(
                emotion = emotion.name,
                count = emotionTags.size,
                avgReturnPct = if (returns.isNotEmpty()) returns.average() else null,
            )
        }

        return EmotionAnalysisResponse(stats = stats)
    }

    private fun EmotionTag.toDto() = EmotionTagDto(
        id = id,
        paperTradeId = paperTradeId,
        userId = userId,
        emotion = emotion.name,
        memo = memo,
        createdAt = createdAt,
    )
}
