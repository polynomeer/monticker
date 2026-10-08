package com.monticker.api.watchrule.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/** 최신 1분봉 종가와 그 시각. */
data class LatestPrice(val close: BigDecimal, val candleTime: Instant)

/**
 * ADR-095 — 규칙 대상(관심종목 그룹)과 발동 가격을 읽는 JDBC 읽기 전용 경로.
 *
 * watchlist·candles 모듈에 의존하지 않고 테이블을 직접 읽는다(사가가 portfolio_positions를 읽는 것과 같은 수준).
 * 그룹은 항상 **소유자 조건과 함께** 본다 — 남의 그룹은 없는 그룹과 같다(security-review H6, 404 동등성).
 */
@Component
class WatchRuleTargets(private val jdbc: JdbcTemplate) {

    fun ownsGroup(userId: Long, groupId: Long): Boolean =
        jdbc.query(
            "SELECT 1 FROM watchlist_groups WHERE id = ? AND user_id = ?",
            { _, _ -> true }, groupId, userId,
        ).isNotEmpty()

    /** 내 그룹 중 [groupIds]의 이름. 지워졌거나 남의 그룹이면 맵에 없다. */
    fun groupNames(userId: Long, groupIds: Collection<Long>): Map<Long, String> {
        if (groupIds.isEmpty()) return emptyMap()
        val placeholders = groupIds.joinToString(",") { "?" }
        val args: Array<Any> = arrayOf(userId, *groupIds.toTypedArray())
        return jdbc.query(
            "SELECT id, name FROM watchlist_groups WHERE user_id = ? AND id IN ($placeholders)",
            { rs, _ -> rs.getLong("id") to rs.getString("name") },
            *args,
        ).toMap()
    }

    /** 발동 가격 — 사가와 같은 출처(최신 1분봉 종가). 봉이 없으면 null. */
    fun latestPrice(stockId: Long): LatestPrice? =
        jdbc.query(
            "SELECT close, candle_time FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            { rs, _ -> LatestPrice(rs.getBigDecimal("close"), rs.getTimestamp("candle_time").toInstant()) },
            stockId,
        ).firstOrNull()
}
