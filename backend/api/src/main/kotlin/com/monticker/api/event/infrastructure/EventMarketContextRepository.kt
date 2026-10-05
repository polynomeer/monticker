package com.monticker.api.event.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/** 이벤트 한 건 주변의 1분봉 원자료. 계산은 EventMarketContext가 한다. */
data class EventCandleWindow(
    val eventId: Long,
    /** 이벤트 분(minute) 직전 마지막 1분봉 종가(최대 하루 전까지) */
    val baseClose: BigDecimal?,
    /** 이벤트 분부터 min(이벤트+구간, 지금)까지의 마지막 1분봉 종가 */
    val endClose: BigDecimal?,
    /** 이벤트 분부터 5분 동안 1분봉 평균 거래량 */
    val eventAvgVolume: Double?,
    /** 이벤트 직전 60분 동안 1분봉 평균 거래량 */
    val baselineAvgVolume: Double?,
)

/**
 * 이벤트 시각 주변의 candles_1m을 한 번의 쿼리로 읽는다(이벤트 수만큼 왕복하지 않는다).
 * candles_1m은 marketdata가 소유하지만 stocks 조인처럼 읽기 전용 SQL 조인은 이 저장소의 관례다
 * (ScreenerRepository·EventTimelineService). 인덱스 (stock_id, candle_time DESC)로 이벤트당 짧은 범위만 읽는다.
 */
@Repository
class EventMarketContextRepository(private val jdbc: JdbcTemplate) {

    companion object {
        /** 한 번에 계산하는 최대 이벤트 수 — /api/events/recent 상한(50)과 같다 */
        const val MAX_EVENTS = 50
        const val WINDOW_MINUTES = 30
        const val EVENT_VOLUME_MINUTES = 5
        const val BASELINE_VOLUME_MINUTES = 60
    }

    data class EventRef(val id: Long, val stockId: Long, val eventTime: Instant)

    fun findWindows(events: List<EventRef>, now: Instant = Instant.now()): List<EventCandleWindow> {
        if (events.isEmpty()) return emptyList()
        val batch = events.take(MAX_EVENTS)
        val values = batch.joinToString(",") { "(?::bigint, ?::bigint, ?::timestamptz)" }
        val args = ArrayList<Any>(batch.size * 3 + 1)
        batch.forEach { args += it.id; args += it.stockId; args += Timestamp.from(it.eventTime) }
        args += Timestamp.from(now)

        // 상수(구간 길이)는 이 클래스의 const라 문자열 보간이 안전하다. 사용자 입력은 모두 바인딩한다.
        val sql = """
            WITH ev(id, stock_id, t) AS (VALUES $values),
                 nowv(n) AS (SELECT ?::timestamptz)
            SELECT ev.id,
              (SELECT c.close FROM candles_1m c
                WHERE c.stock_id = ev.stock_id
                  AND c.candle_time <  date_trunc('minute', ev.t)
                  AND c.candle_time >= ev.t - interval '1 day'
                ORDER BY c.candle_time DESC LIMIT 1) AS base_close,
              (SELECT c.close FROM candles_1m c, nowv
                WHERE c.stock_id = ev.stock_id
                  AND c.candle_time >= date_trunc('minute', ev.t)
                  AND c.candle_time <= LEAST(ev.t + interval '$WINDOW_MINUTES minutes', nowv.n)
                ORDER BY c.candle_time DESC LIMIT 1) AS end_close,
              (SELECT AVG(c.volume) FROM candles_1m c
                WHERE c.stock_id = ev.stock_id
                  AND c.candle_time >= date_trunc('minute', ev.t)
                  AND c.candle_time <  date_trunc('minute', ev.t) + interval '$EVENT_VOLUME_MINUTES minutes') AS ev_vol,
              (SELECT AVG(c.volume) FROM candles_1m c
                WHERE c.stock_id = ev.stock_id
                  AND c.candle_time >= date_trunc('minute', ev.t) - interval '$BASELINE_VOLUME_MINUTES minutes'
                  AND c.candle_time <  date_trunc('minute', ev.t)) AS base_vol
            FROM ev
        """.trimIndent()

        return jdbc.query(sql, { rs, _ ->
            EventCandleWindow(
                eventId           = rs.getLong("id"),
                baseClose         = rs.getBigDecimal("base_close"),
                endClose          = rs.getBigDecimal("end_close"),
                eventAvgVolume    = rs.getBigDecimal("ev_vol")?.toDouble(),
                baselineAvgVolume = rs.getBigDecimal("base_vol")?.toDouble(),
            )
        }, *args.toTypedArray())
    }
}
