package com.monticker.api.event.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 유형 하나의 건수와 그 유형 이벤트가 난 종목 수 */
data class EventTypeTally(val eventType: String, val count: Long, val stockCount: Long)

/**
 * stock_events 집계 쿼리(ADR-087). 시각 경계는 호출부가 KST 자정을 Instant로 바꿔 넘기고,
 * 여기서는 timestamptz끼리 `[from, to)` 반열림 구간으로만 비교한다 — `AT TIME ZONE`을 컬럼에 씌워
 * 비교하면 세션 타임존에 따라 결과가 달라진다(CI에서만 깨졌던 전례).
 *
 * 바인딩은 OffsetDateTime(UTC) — java.sql.Timestamp는 JVM 기본 타임존을 거쳐 해석돼 같은 함정이 생길 수 있다.
 */
@Repository
class EventAggregateRepository(private val jdbc: JdbcTemplate) {

    private fun ts(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

    /** [from, to) 구간 전 종목 이벤트를 유형별로 센다. idx_stock_events_time_type_stock(V84)를 탄다 */
    fun countByType(from: Instant, to: Instant): List<EventTypeTally> = jdbc.query(
        """
        SELECT event_type, COUNT(*) AS cnt, COUNT(DISTINCT stock_id) AS stocks
        FROM stock_events
        WHERE event_time >= ? AND event_time < ?
        GROUP BY event_type
        """.trimIndent(),
        { rs, _ -> EventTypeTally(rs.getString("event_type"), rs.getLong("cnt"), rs.getLong("stocks")) },
        ts(from), ts(to),
    )

    /**
     * 종목별 [from, to) 이벤트 수. 이벤트가 없는 종목은 결과에 없다(호출부가 0으로 채운다).
     * 종목 수는 호출부가 상한으로 자른다 — IN 목록은 전부 `?` 바인딩이다.
     */
    fun countByStock(stockIds: Collection<Long>, from: Instant, to: Instant): Map<Long, Long> {
        if (stockIds.isEmpty()) return emptyMap()
        val placeholders = stockIds.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT stock_id, COUNT(*) AS cnt
            FROM stock_events
            WHERE stock_id IN ($placeholders) AND event_time >= ? AND event_time < ?
            GROUP BY stock_id
            """.trimIndent(),
            { rs, _ -> rs.getLong("stock_id") to rs.getLong("cnt") },
            *(stockIds.toList<Any>() + ts(from) + ts(to)).toTypedArray(),
        ).toMap()
    }
}
