package com.monticker.api.watchrule.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-077 — 발동 직전의 두 가지 판정: 하루 최대 발동 슬롯과 복합 조건(동반 이벤트).
 *
 * 하루 슬롯은 "센 다음 주문"이 아니라 **조건부 UPSERT 한 문장**으로 잡는다. 같은 규칙에 이벤트가 동시에 두 개 와도
 * ON CONFLICT DO UPDATE가 행을 잠그고 최신 값으로 WHERE를 다시 평가하므로 한도를 넘는 두 번째 슬롯은 0행이다.
 * 실행기는 트랜잭션 밖이라(주문 거부 기록을 남기려고) 이 문장은 자동 커밋된다.
 */
@Component
class WatchRuleGuards(private val jdbc: JdbcTemplate) {

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        const val CLAIM_SQL = """
            INSERT INTO watch_rule_daily_counts (watch_rule_id, day, executed) VALUES (?, ?, 1)
            ON CONFLICT (watch_rule_id, day) DO UPDATE SET executed = watch_rule_daily_counts.executed + 1
            WHERE watch_rule_daily_counts.executed < ?
            RETURNING executed
        """
        const val RELEASE_SQL =
            "UPDATE watch_rule_daily_counts SET executed = executed - 1 WHERE watch_rule_id = ? AND day = ? AND executed > 0"
    }

    fun today(): LocalDate = LocalDate.now(ZONE)

    /**
     * 오늘 슬롯 하나를 잡는다. [limit]이 null이면 항상 잡히고(카드의 "오늘 발동" 집계용), 아니면 한도 안에서만.
     * @return 잡았으면 true
     */
    fun claimDailySlot(ruleId: Long, limit: Int?): Boolean {
        val day = Date.valueOf(today())
        // 새 행(오늘 첫 발동)은 INSERT 경로라 WHERE가 걸리지 않는다 — limit ≥ 1(CHECK)이므로 항상 허용이 맞다.
        val rows = jdbc.query(CLAIM_SQL.trimIndent(), { rs, _ -> rs.getInt("executed") }, ruleId, day, limit ?: Int.MAX_VALUE)
        return rows.isNotEmpty()
    }

    /** 슬롯을 잡았지만 주문이 나가지 않았다(거부·인프라 오류) — 돌려준다. */
    fun releaseDailySlot(ruleId: Long) {
        jdbc.update(RELEASE_SQL, ruleId, Date.valueOf(today()))
    }

    /** 오늘(KST) 규칙별 체결 수. */
    fun todayCounts(ruleIds: Collection<Long>): Map<Long, Int> {
        if (ruleIds.isEmpty()) return emptyMap()
        val placeholders = ruleIds.joinToString(",") { "?" }
        val args: Array<Any> = arrayOf(Date.valueOf(today()), *ruleIds.toTypedArray())
        return jdbc.query(
            "SELECT watch_rule_id, executed FROM watch_rule_daily_counts WHERE day = ? AND watch_rule_id IN ($placeholders)",
            { rs, _ -> rs.getLong("watch_rule_id") to rs.getInt("executed") },
            *args,
        ).toMap()
    }

    /**
     * 복합 조건 — [eventTime] 앞 [windowSec] 안에(주 이벤트 시각 포함) 이 종목에서 감지되지 않은 유형을 돌려준다.
     * 빈 목록이면 조건 충족.
     */
    fun missingRequiredEvents(stockId: Long, required: List<String>, eventTime: Instant, windowSec: Int): List<String> {
        if (required.isEmpty()) return emptyList()
        val placeholders = required.joinToString(",") { "?" }
        val args: Array<Any> = arrayOf(
            stockId, Timestamp.from(eventTime.minusSeconds(windowSec.toLong())), Timestamp.from(eventTime), *required.toTypedArray(),
        )
        val seen = jdbc.query(
            """
            SELECT DISTINCT event_type FROM stock_events
            WHERE stock_id = ? AND event_time >= ? AND event_time <= ? AND event_type IN ($placeholders)
            """.trimIndent(),
            { rs, _ -> rs.getString("event_type") },
            *args,
        ).toSet()
        return required.filter { it !in seen }
    }
}
