package com.monticker.api.wallet.application

import com.monticker.api.common.time.KstPeriod
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.wallet.domain.EmotionTag
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

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
    /** ADR-091 — 구간 안 태그된 거래 중 이 감정의 비중(%) */
    val sharePct: Double? = null,
)

data class EmotionAnalysisResponse(
    val stats: List<EmotionStat>,
    /** ADR-091 — 조회 구간(KST, 양 끝 포함). 전체 기간이면 null */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** 구간 안의 태그된 거래 수(분포의 분모) */
    val totalCount: Int = 0,
)

/** 집계 입력 — 태그 1건과 그 거래, 매수라면 그 뒤 같은 종목의 첫 매도가. */
data class EmotionRow(val emotion: String, val side: String, val price: BigDecimal, val nextSellPrice: BigDecimal?)

object EmotionAnalysis {
    /**
     * 감정별 건수·비중(%)과 평균 수익률. 수익률은 **매수 태그만**: (그 뒤 첫 매도가 − 매수가) ÷ 매수가 × 100.
     * 아직 팔지 않은 매수와 매도 태그는 수익률에서 빠진다(건수에는 들어간다). 건수 내림차순.
     */
    fun aggregate(rows: List<EmotionRow>, period: KstPeriod? = null): EmotionAnalysisResponse {
        val total = rows.size
        val stats = rows.groupBy { it.emotion }.map { (emotion, rs) ->
            val returns = rs.mapNotNull { r ->
                val sell = r.nextSellPrice ?: return@mapNotNull null
                if (r.side != "BUY" || r.price.signum() <= 0) return@mapNotNull null
                sell.subtract(r.price).divide(r.price, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble()
            }
            EmotionStat(
                emotion = emotion,
                count = rs.size,
                avgReturnPct = if (returns.isNotEmpty()) returns.average() else null,
                sharePct = rs.size * 100.0 / total,
            )
        }.sortedWith(compareByDescending<EmotionStat> { it.count }.thenBy { it.emotion })
        return EmotionAnalysisResponse(stats, period?.from, period?.to, total)
    }
}

@Service
@Transactional
class EmotionTagService(
    private val emotionTagRepo: EmotionTagRepository,
    private val tradeQueryService: PaperTradeQueryService,
    private val jdbc: JdbcTemplate,
) {

    fun saveTag(userId: Long, tradeId: Long, emotion: String, memo: String?): EmotionTagDto {
        requireOwnedTrade(userId, tradeId)
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
    fun getTag(userId: Long, tradeId: Long): EmotionTagDto? {
        requireOwnedTrade(userId, tradeId)
        // 수정 전 IDOR로 다른 사용자가 심어둔 태그가 남아 있을 수 있으므로 태그 쪽 소유자도 확인한다
        return emotionTagRepo.findByPaperTradeId(tradeId)
            ?.takeIf { it.userId == userId }
            ?.toDto()
    }

    /**
     * ADR-091 — 감정별 분포와 평균 수익률. [period]는 **거래 체결 시각** 기준 KST 구간이다(null = 전체 기간, 기존 동작).
     * 거래·태그·"그 매수 뒤 첫 매도가"를 한 쿼리로 읽는다 — 예전엔 태그마다 거래 조회와 매도가 조회를 따로 했다(N+1).
     */
    @Transactional(readOnly = true)
    fun getAnalysis(userId: Long, period: KstPeriod? = null): EmotionAnalysisResponse {
        val periodSql = if (period != null) " AND t.traded_at >= ? AND t.traded_at < ?" else ""
        val args = mutableListOf<Any>(userId)
        if (period != null) {
            args += Timestamp.from(period.start)
            args += Timestamp.from(period.endExclusive)
        }
        val rows = jdbc.query(
            """SELECT et.emotion, t.side, t.price, ns.price AS next_sell_price
               FROM order_emotion_tags et
               JOIN paper_trades t ON t.id = et.paper_trade_id AND t.user_id = et.user_id
               LEFT JOIN LATERAL (
                   SELECT s.price FROM paper_trades s
                   WHERE s.user_id = t.user_id AND s.stock_id = t.stock_id AND s.side = 'SELL' AND s.traded_at > t.traded_at
                   ORDER BY s.traded_at ASC LIMIT 1
               ) ns ON t.side = 'BUY'
               WHERE et.user_id = ?$periodSql""",
            { rs, _ -> EmotionRow(rs.getString("emotion"), rs.getString("side"), rs.getBigDecimal("price"), rs.getBigDecimal("next_sell_price")) },
            *args.toTypedArray(),
        )
        return EmotionAnalysis.aggregate(rows, period)
    }

    /**
     * 남의 거래는 존재하지 않는 거래와 똑같이 404로 응답한다 — 403/400으로 구분하면
     * paper trade id의 존재 여부를 열거할 수 있다.
     */
    private fun requireOwnedTrade(userId: Long, tradeId: Long) {
        val trade = tradeQueryService.findById(tradeId)
        if (trade == null || trade.userId != userId) {
            throw NoSuchElementException("Paper trade not found: $tradeId")
        }
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
