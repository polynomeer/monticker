package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

data class MarketSignalItem(
    val id: Long,
    val marketId: Long,
    val rulesetId: String,
    val strategyName: String,
    val direction: String,
    val stockId: Long,
    /** V76 이전 신호는 null */
    val price: BigDecimal?,
    val evalDate: LocalDate?,
    val signalTime: String,
)

data class MarketSignalFeed(
    val items: List<MarketSignalItem>,
    /** 이번 달(KST) 구독 전략 신호 수 — items의 limit와 무관한 전체 개수 */
    val thisMonthCount: Long,
)

/**
 * 구독 전략 신호 이력(ADR-035 접근 제어 유지). 예전에는 화면을 연 뒤 WS로 들어온 신호만 보였다.
 * - 구독 피드: 사용자가 구독 중인 마켓 전략의 신호만. 남의 전략 신호는 마켓 ID를 알아도 볼 수 없다.
 * - 전략별 이력: 그 전략의 제작자이거나 구독자일 때만. 아니면 403.
 * 응답에는 신호(방향·종목·가격·날짜)만 있고 룰 정의는 없다.
 */
@Service
class MarketSignalQueryService(
    private val jdbc: JdbcTemplate,
    private val ruleSetRepository: RuleSetRepository,
) {
    companion object {
        const val MAX_LIMIT = 100
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    fun subscribedFeed(userId: Long, limit: Int, today: LocalDate = LocalDate.now(KST)): MarketSignalFeed {
        val n = limit.coerceIn(1, MAX_LIMIT)
        val rows = jdbc.query(
            """SELECT qs.id, sm.id AS market_id, qs.rule_set_id, qs.direction, qs.stock_id, qs.price, qs.eval_date, qs.signal_time
               FROM quant_signals qs
               JOIN strategy_market sm ON sm.ruleset_id = qs.rule_set_id
               JOIN strategy_subscriptions ss ON ss.market_id = sm.id AND ss.user_id = ?
               ORDER BY qs.signal_time DESC, qs.id DESC
               LIMIT ?""",
            { rs, _ -> RawSignal(
                id = rs.getLong("id"), marketId = rs.getLong("market_id"), rulesetId = rs.getString("rule_set_id"),
                direction = rs.getString("direction"), stockId = rs.getLong("stock_id"), price = rs.getBigDecimal("price"),
                evalDate = rs.getDate("eval_date")?.toLocalDate(), signalTime = rs.getTimestamp("signal_time").toInstant().toString(),
            ) },
            userId, n,
        )
        val monthStart = Timestamp.from(today.withDayOfMonth(1).atStartOfDay(KST).toInstant())
        val count = jdbc.queryForObject(
            """SELECT COUNT(*) FROM quant_signals qs
               JOIN strategy_market sm ON sm.ruleset_id = qs.rule_set_id
               JOIN strategy_subscriptions ss ON ss.market_id = sm.id AND ss.user_id = ?
               WHERE qs.signal_time >= ?""",
            Long::class.java, userId, monthStart,
        ) ?: 0L
        return MarketSignalFeed(withNames(rows), count)
    }

    fun strategyHistory(userId: Long, marketId: Long, limit: Int): List<MarketSignalItem> {
        val market = jdbc.query(
            "SELECT ruleset_id, user_id FROM strategy_market WHERE id = ?",
            { rs, _ -> rs.getString("ruleset_id") to rs.getLong("user_id") }, marketId,
        ).firstOrNull() ?: throw NoSuchElementException("전략을 찾을 수 없습니다: $marketId")
        val (rulesetId, creatorId) = market
        if (creatorId != userId) {
            val subscribed = (jdbc.queryForObject(
                "SELECT COUNT(*) FROM strategy_subscriptions WHERE market_id = ? AND user_id = ?",
                Long::class.java, marketId, userId,
            ) ?: 0L) > 0
            if (!subscribed) throw AccessDeniedException("이 전략을 구독해야 신호 이력을 볼 수 있습니다.")
        }
        val rows = jdbc.query(
            """SELECT id, rule_set_id, direction, stock_id, price, eval_date, signal_time
               FROM quant_signals WHERE rule_set_id = ?
               ORDER BY signal_time DESC, id DESC LIMIT ?""",
            { rs, _ -> RawSignal(
                id = rs.getLong("id"), marketId = marketId, rulesetId = rs.getString("rule_set_id"),
                direction = rs.getString("direction"), stockId = rs.getLong("stock_id"), price = rs.getBigDecimal("price"),
                evalDate = rs.getDate("eval_date")?.toLocalDate(), signalTime = rs.getTimestamp("signal_time").toInstant().toString(),
            ) },
            rulesetId, limit.coerceIn(1, MAX_LIMIT),
        )
        return withNames(rows)
    }

    private data class RawSignal(
        val id: Long, val marketId: Long, val rulesetId: String, val direction: String, val stockId: Long,
        val price: BigDecimal?, val evalDate: LocalDate?, val signalTime: String,
    )

    // ruleset_id는 Mongo 문서 ID라 SQL JOIN이 안 된다 — 이름은 한 번에 따로 읽는다.
    private fun withNames(rows: List<RawSignal>): List<MarketSignalItem> {
        val names = ruleSetRepository.findAllById(rows.map { it.rulesetId }.distinct()).associate { it.id to it.name }
        return rows.map {
            MarketSignalItem(it.id, it.marketId, it.rulesetId, names[it.rulesetId] ?: "(삭제된 전략)", it.direction,
                it.stockId, it.price, it.evalDate, it.signalTime)
        }
    }
}
