package com.monticker.api.wallet.application

import com.monticker.api.common.time.KstPeriod
import com.monticker.api.paper.application.PaperRealizedPnlService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** 분자 ÷ 분모. 분모가 0이면 [pct]는 null(화면 "—"). */
data class Ratio(val numerator: Int, val denominator: Int) {
    val pct: Double? get() = if (denominator == 0) null else numerator * 100.0 / denominator

    companion object {
        /** null = 판정 불가(분모에서 뺀다), true = 분자에 넣는다. */
        fun of(flags: Collection<Boolean?>): Ratio = Ratio(flags.count { it == true }, flags.count { it != null })
    }
}

/** 이번 주(월요일 00:00 KST ~ 지금)와 지난주(월~일) 값. [deltaPp] = 이번 주 − 지난주(%p), 한쪽이라도 null이면 null. */
data class WeeklyRatio(val thisWeek: Ratio, val lastWeek: Ratio) {
    val deltaPp: Double? get() = thisWeek.pct?.let { a -> lastWeek.pct?.let { b -> a - b } }
}

/** 주간 평균 행동 점수(그 주에 계산된 일별 점수의 평균). [delta] = 이번 주 − 지난주. */
data class WeeklyScore(val thisWeekAvg: Double?, val lastWeekAvg: Double?, val thisWeekDays: Int, val lastWeekDays: Int) {
    val delta: Double? get() = thisWeekAvg?.let { a -> lastWeekAvg?.let { b -> a - b } }
}

data class ScoreDetails(
    /** 이번 주 월요일(KST) */
    val weekStart: LocalDate,
    val lastWeekStart: LocalDate,
    /** ADR-085 계획된 주문 ÷ 판정 가능한 주문 — 리플레이와 같은 정의([ReplayService.isPlanned]) */
    val planAdherence: WeeklyRatio,
    /** ADR-091 손절 준수율 — 손절을 정해 둔 손실 매도 중 그 손절선을 지킨 비율 */
    val stopLossAdherence: WeeklyRatio,
    val behaviorScore: WeeklyScore,
)

/** 손절 준수 판정 입력 — 손실로 끝난 매도 체결 1건. */
data class LosingExit(
    val tradeId: Long,
    val stockId: Long,
    val price: BigDecimal,
    val tradedAt: Instant,
    /** [com.monticker.api.paper.application.RealizedPnl.positionSince] — 이 포지션 직전에 보유가 0이 된 시각 */
    val positionSince: Instant?,
    val origin: String?,
    val originRef: Long?,
)

/** 사용자가 정한 손절(모의 조건부 주문 STOP_LOSS). 취소·실패 여부와 무관하게 "정했다"는 기록이다. */
data class StopLossDef(val id: Long, val stockId: Long, val triggerPrice: BigDecimal, val createdAt: Instant)

/**
 * ADR-091 손절 준수율.
 *
 * - 대상: 기간 안의 손실 매도(이동평균 실현 손익 < 0) 중 **그 포지션에 손절을 정해 둔** 것. 정해 둔 손절이 없으면 뺀다.
 * - 그 포지션의 손절(기준 손절) = 같은 종목의 STOP_LOSS 중 `positionSince ≤ 등록 시각 ≤ 매도 시각`인 것 가운데 **가장 먼저 등록한 것**
 *   (처음 세운 계획). 나중에 손절을 내리거나 지웠어도 기준은 처음 손절이다.
 * - 지킴: 기준 손절이 직접 발동해 나간 매도(출처 CONDITIONAL, ref = 기준 손절 — 갭으로 손절가 아래 체결돼도 지킴)
 *   이거나, 매도 가격 ≥ 기준 손절가(손절선에 닿기 전이나 그 가격에 스스로 나감).
 * - 못 지킴: 그 밖의 경우 — 손절가보다 낮은 가격에 나감(손절을 내리거나 지운 뒤 더 떨어져서 판 경우 등).
 */
object StopLossAdherence {
    fun referenceStop(exit: LosingExit, stops: List<StopLossDef>): StopLossDef? =
        stops.asSequence()
            .filter { it.stockId == exit.stockId && !it.createdAt.isAfter(exit.tradedAt) }
            .filter { s -> exit.positionSince == null || !s.createdAt.isBefore(exit.positionSince) }
            .minWithOrNull(compareBy<StopLossDef> { it.createdAt }.thenBy { it.id })

    /** null = 손절을 정하지 않아 판정 대상이 아님 */
    fun respected(exit: LosingExit, stops: List<StopLossDef>): Boolean? {
        val ref = referenceStop(exit, stops) ?: return null
        if (exit.origin == "CONDITIONAL" && exit.originRef == ref.id) return true
        return exit.price >= ref.triggerPrice
    }

    fun ratio(exits: List<LosingExit>, stops: List<StopLossDef>): Ratio = Ratio.of(exits.map { respected(it, stops) })
}

/**
 * /wallet 점수 카드의 세부 지표(ADR-091): 계획 준수율·손절 준수율·행동 점수의 지난주 대비. KST 주(월~일).
 * 경계는 Kotlin에서 계산해 Instant로 바인딩한다. 쿼리 수는 거래 수와 무관하다(거래·태그 1, 실현 손익 2, 손절 1, 점수 1).
 */
@Service
@Transactional(readOnly = true)
class ScoreDetailService(
    private val jdbc: JdbcTemplate,
    private val realizedPnlService: PaperRealizedPnlService,
    /** 테스트가 주 경계를 고정한다. 운영은 기본값(Clock 빈 없음). */
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class TradeRow(
        val id: Long, val stockId: Long, val side: String, val price: BigDecimal, val tradedAt: Instant,
        val origin: String?, val originRef: Long?, val emotion: String?,
    )

    fun weekly(userId: Long): ScoreDetails {
        val now = clock.instant()
        val thisWeek = KstPeriod.weekOf(KstPeriod.today(now))
        val lastWeek = KstPeriod.weekOf(thisWeek.from.minusDays(1))
        val rangeStart = lastWeek.start
        val rangeEnd = thisWeek.endExclusive

        // 거래 + 감정 태그(태그 소유자 = 거래 소유자) 한 번에
        val trades = jdbc.query(
            """SELECT t.id, t.stock_id, t.side, t.price, t.traded_at, t.origin, t.origin_ref, et.emotion
               FROM paper_trades t
               LEFT JOIN order_emotion_tags et ON et.paper_trade_id = t.id AND et.user_id = t.user_id
               WHERE t.user_id = ? AND t.traded_at >= ? AND t.traded_at < ?""",
            { rs, _ ->
                TradeRow(
                    rs.getLong("id"), rs.getLong("stock_id"), rs.getString("side"), rs.getBigDecimal("price"),
                    rs.getTimestamp("traded_at").toInstant(), rs.getString("origin"),
                    (rs.getObject("origin_ref") as Number?)?.toLong(), rs.getString("emotion"),
                )
            },
            userId, Timestamp.from(rangeStart), Timestamp.from(rangeEnd),
        )
        fun inWeek(t: TradeRow, w: KstPeriod) = !t.tradedAt.isBefore(w.start) && t.tradedAt.isBefore(w.endExclusive)

        // 계획 준수율 — 리플레이와 같은 판정 함수
        fun plan(w: KstPeriod) = Ratio.of(trades.filter { inWeek(it, w) }.map { ReplayService.isPlanned(it.origin, it.emotion) })

        // 손절 준수율
        val sells = trades.filter { it.side == "SELL" }
        val pnl = realizedPnlService.forSells(userId, sells.map { it.id })
        val losing = sells.mapNotNull { t ->
            val r = pnl[t.id]?.takeIf { it.pnl.signum() < 0 } ?: return@mapNotNull null
            t to LosingExit(t.id, t.stockId, t.price, t.tradedAt, r.positionSince, t.origin, t.originRef)
        }
        val stops = stopLossesFor(userId, losing.map { it.second.stockId }.distinct(), rangeEnd)
        fun stop(w: KstPeriod) = StopLossAdherence.ratio(losing.filter { inWeek(it.first, w) }.map { it.second }, stops)

        // 행동 점수 주간 평균 — score_date는 KST 날짜(BehaviorScoreService)
        val scores = jdbc.query(
            "SELECT score_date, behavior_score FROM investment_behavior_scores WHERE user_id = ? AND score_date >= ? AND score_date <= ? AND behavior_score IS NOT NULL",
            { rs, _ -> rs.getObject("score_date", LocalDate::class.java) to rs.getInt("behavior_score") },
            userId, lastWeek.from, thisWeek.to,
        )
        fun avg(w: KstPeriod) = scores.filter { !it.first.isBefore(w.from) && !it.first.isAfter(w.to) }.map { it.second }
        val tw = avg(thisWeek)
        val lw = avg(lastWeek)

        return ScoreDetails(
            weekStart = thisWeek.from,
            lastWeekStart = lastWeek.from,
            planAdherence = WeeklyRatio(plan(thisWeek), plan(lastWeek)),
            stopLossAdherence = WeeklyRatio(stop(thisWeek), stop(lastWeek)),
            behaviorScore = WeeklyScore(
                thisWeekAvg = tw.takeIf { it.isNotEmpty() }?.average(),
                lastWeekAvg = lw.takeIf { it.isNotEmpty() }?.average(),
                thisWeekDays = tw.size,
                lastWeekDays = lw.size,
            ),
        )
    }

    private fun stopLossesFor(userId: Long, stockIds: List<Long>, until: Instant): List<StopLossDef> {
        if (stockIds.isEmpty()) return emptyList()
        return jdbc.query(
            """SELECT id, stock_id, trigger_price, created_at FROM paper_conditional_orders
               WHERE user_id = ? AND trigger_type = 'STOP_LOSS' AND created_at < ?
                 AND stock_id IN (${stockIds.joinToString(",") { "?" }})""",
            { rs, _ ->
                StopLossDef(rs.getLong("id"), rs.getLong("stock_id"), rs.getBigDecimal("trigger_price"), rs.getTimestamp("created_at").toInstant())
            },
            userId, Timestamp.from(until), *stockIds.toTypedArray(),
        )
    }
}
