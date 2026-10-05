package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

data class StockSignalResponse(
    val id: Long,
    val ruleSetId: String,
    val ruleSetName: String,
    /** 내 전략(OWNED) / 구독 전략(SUBSCRIBED) */
    val origin: String,
    val direction: String,
    val signalTime: Instant,
    val mode: String,
)

/**
 * 종목 차트의 "퀀트 시그널" 레이어 — 이 종목에 대해 **내가 볼 수 있는** 전략의 신호만.
 *
 * 볼 수 있는 전략 = 내 룰셋 + 구독한 마켓 전략. ADR-035의 WS 구독 접근 규칙(RuleSetSignalAccessInterceptor)과
 * 같은 기준이다 — 다른 사용자의 유료 신호가 차트 API로 새지 않도록 SQL의 rule_set_id 목록을 서버가 정한다.
 */
@Service
class StockSignalQueryService(
    private val ruleSetRepository: RuleSetRepository,
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun signalsForStock(userId: Long, stockId: Long, days: Int): List<StockSignalResponse> {
        val owned = ruleSetRepository.findAllByUserId(userId).mapNotNull { r -> r.id?.let { it to r.name } }.toMap()
        val subscribedIds = jdbc.query(
            """
            SELECT sm.ruleset_id FROM strategy_subscriptions ss
            JOIN strategy_market sm ON sm.id = ss.market_id
            WHERE ss.user_id = :userId
            """.trimIndent(),
            MapSqlParameterSource("userId", userId),
        ) { rs, _ -> rs.getString("ruleset_id") }.filterNotNull().filter { it !in owned }.toSet()
        val subscribed = if (subscribedIds.isEmpty()) emptyMap()
            else ruleSetRepository.findAllById(subscribedIds).mapNotNull { r -> r.id?.let { it to r.name } }.toMap()

        val visible = owned.keys + subscribedIds
        if (visible.isEmpty()) return emptyList()

        val since = Instant.now().minus(days.coerceIn(1, 3650).toLong(), ChronoUnit.DAYS)
        return jdbc.query(
            """
            SELECT id, rule_set_id, direction, signal_time, mode FROM quant_signals
            WHERE stock_id = :stockId AND rule_set_id IN (:ids) AND signal_time >= :since
            ORDER BY signal_time DESC
            LIMIT 500
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("stockId", stockId)
                .addValue("ids", visible)
                .addValue("since", Timestamp.from(since)),
        ) { rs, _ ->
            val rid = rs.getString("rule_set_id")
            StockSignalResponse(
                id = rs.getLong("id"),
                ruleSetId = rid,
                ruleSetName = owned[rid] ?: subscribed[rid] ?: "전략",
                origin = if (rid in owned) "OWNED" else "SUBSCRIBED",
                direction = rs.getString("direction"),
                signalTime = rs.getTimestamp("signal_time").toInstant(),
                mode = rs.getString("mode"),
            )
        }
    }
}
