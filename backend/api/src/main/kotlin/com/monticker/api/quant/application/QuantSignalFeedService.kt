package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

/**
 * 홈 "퀀트 시그널" 패널 — 내가 볼 수 있는 룰셋의 포워드 테스트 신호를 시간 역순으로 모은다.
 *
 * ADR-035 접근 규칙을 그대로 따른다: **내 룰셋**(소유자) 또는 **구독한 마켓 전략**의 룰셋만.
 * RuleSetSignalAccessInterceptor(WS 구독)와 같은 기준이어야 REST가 WS보다 넓은 구멍이 되지 않는다.
 */
@Service
class QuantSignalFeedService(
    private val ruleSetRepository: RuleSetRepository,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        const val MAX_LIMIT = 50
        /** IN 목록 상한 — 룰셋이 이보다 많으면 최근 수정 순으로 자른다 */
        const val MAX_RULESETS = 500
    }

    enum class Source { MINE, SUBSCRIBED }

    /** 접근 가능한 룰셋 id → (이름, 출처). 내 룰셋이 구독 전략보다 우선한다. */
    fun accessibleRuleSets(userId: Long): Map<String, Pair<String, Source>> {
        val mine = ruleSetRepository.findAllByUserId(userId)
            .filter { it.id != null }
            .sortedByDescending { it.updatedAt }
            .take(MAX_RULESETS)
            .associate { it.id!! to (it.name to Source.MINE) }
        val subscribedIds = jdbc.queryForList(
            """
            SELECT sm.ruleset_id FROM strategy_subscriptions ss
            JOIN strategy_market sm ON sm.id = ss.market_id
            WHERE ss.user_id = ?
            ORDER BY ss.created_at DESC
            LIMIT ?
            """.trimIndent(),
            String::class.java, userId, MAX_RULESETS,
        ).filter { it !in mine }
        val subscribed = if (subscribedIds.isEmpty()) emptyMap() else {
            val names = ruleSetRepository.findAllById(subscribedIds).associate { it.id to it.name }
            subscribedIds.associateWith { (names[it] ?: "(삭제된 전략)") to Source.SUBSCRIBED }
        }
        return mine + subscribed
    }

    fun feed(userId: Long, limit: Int): List<QuantSignalFeedItem> {
        val ruleSets = accessibleRuleSets(userId)
        if (ruleSets.isEmpty()) return emptyList()
        val ids = ruleSets.keys.toList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT qs.id, qs.rule_set_id, qs.stock_id, qs.direction, qs.signal_time, s.symbol, s.name
            FROM quant_signals qs
            JOIN stocks s ON s.id = qs.stock_id
            WHERE qs.rule_set_id IN ($placeholders)
            ORDER BY qs.signal_time DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val rsId = rs.getString("rule_set_id")
                val (name, source) = ruleSets.getValue(rsId)
                QuantSignalFeedItem(
                    id          = rs.getLong("id"),
                    ruleSetId   = rsId,
                    ruleSetName = name,
                    source      = source.name,
                    stockId     = rs.getLong("stock_id"),
                    symbol      = rs.getString("symbol"),
                    stockName   = rs.getString("name"),
                    direction   = rs.getString("direction"),
                    signalTime  = rs.getTimestamp("signal_time").toInstant(),
                )
            },
            *(ids + limit.coerceIn(1, MAX_LIMIT)).toTypedArray(),
        )
    }

    /**
     * [since] 이후 내가 볼 수 있는 룰셋에서 신호가 난 종목 id. 스크리너 "퀀트 시그널 발생" 필터용 —
     * 남의 비공개 룰셋·구독하지 않은 유료 전략의 신호 존재 여부가 새지 않게 같은 접근 규칙을 쓴다.
     */
    fun stockIdsWithSignalsSince(userId: Long, since: Instant): Set<Long> {
        val ids = accessibleRuleSets(userId).keys.toList()
        if (ids.isEmpty()) return emptySet()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbc.queryForList(
            "SELECT DISTINCT stock_id FROM quant_signals WHERE rule_set_id IN ($placeholders) AND signal_time >= ?",
            Long::class.java, *(ids + Timestamp.from(since)).toTypedArray(),
        ).toSet()
    }
}

data class QuantSignalFeedItem(
    val id: Long,
    val ruleSetId: String,
    val ruleSetName: String,
    /** MINE | SUBSCRIBED */
    val source: String,
    val stockId: Long,
    val symbol: String,
    val stockName: String,
    /** BUY | SELL */
    val direction: String,
    val signalTime: Instant,
)
