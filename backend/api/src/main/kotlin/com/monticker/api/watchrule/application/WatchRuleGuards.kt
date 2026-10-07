package com.monticker.api.watchrule.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [WatchRuleGuards.claimFiring]의 결과. */
sealed interface FiringClaim {
    /** 발동권을 얻었다. 주문이 나가지 않으면 [WatchRuleGuards.releaseFiring]으로 이 값을 돌려준다. */
    data class Claimed(val ruleId: Long, val firedAt: Instant, val previousFiredAt: Instant?, val day: LocalDate) : FiringClaim
    data class InCooldown(val cooldownSec: Int) : FiringClaim
    data class DailyLimitReached(val limit: Int) : FiringClaim
    /** 조회 이후 규칙이 비활성화·삭제됐다. */
    data object Inactive : FiringClaim
}

/**
 * ADR-077 — 발동 직전의 판정: 쿨다운·하루 최대 발동(한 트랜잭션)과 복합 조건(동반 이벤트).
 *
 * **쿨다운 + 하루 슬롯 + 발동 기록은 한 트랜잭션, 규칙 행 잠금 아래에서 한 번에 한다**([claimFiring]).
 * 예전엔 쿨다운을 "최근 EXECUTED 기록이 있는가"로 따로 조회했는데, 그 기록은 체결 뒤에야 생기므로 같은 규칙에 서로 다른
 * 이벤트가 동시에 오면 둘 다 통과해 두 번 체결됐다(보안 리뷰 2026-10). 이제 두 번째 평가는 첫 번째가 커밋할 때까지
 * `FOR UPDATE`에서 기다린 뒤 갱신된 `last_fired_at`을 보고 쿨다운으로 떨어진다.
 *
 * 하루 슬롯은 여전히 조건부 UPSERT 한 문장이다 — ON CONFLICT DO UPDATE가 행을 잠그고 최신 값으로 WHERE를 다시 평가한다.
 * 잠금 순서는 항상 watch_rules → watch_rule_daily_counts(획득·반환 모두)라 교착이 생기지 않는다.
 * 실행기는 트랜잭션 밖이라(주문 거부 기록을 남기려고) 이 판정만 자기 짧은 트랜잭션을 연다 — 주문 제출은 그 밖이다.
 */
@Component
class WatchRuleGuards(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate) {

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

        /**
         * 규칙 행을 잠그고 잠근 시점의 최신 설정으로 쿨다운을 판정한다. clock_timestamp()는 문장 실행 시각이다 —
         * now()(트랜잭션 시작 시각)를 쓰면 잠금을 기다린 평가가 앞선 평가보다 이른 시각을 기록할 수 있다.
         */
        const val LOCK_RULE_SQL = """
            SELECT last_fired_at, cooldown_sec, daily_limit, clock_timestamp() AS fired_at,
                   (cooldown_sec = 0 OR last_fired_at IS NULL
                    OR last_fired_at <= clock_timestamp() - make_interval(secs => cooldown_sec)) AS ready
            FROM watch_rules WHERE id = ? AND is_active
            FOR UPDATE
        """
        const val MARK_FIRED_SQL = "UPDATE watch_rules SET last_fired_at = ? WHERE id = ?"
        /** 우리가 쓴 값일 때만 되돌린다 — 그 사이 다른 발동이 덮어썼으면 그대로 둔다. */
        const val UNMARK_FIRED_SQL = "UPDATE watch_rules SET last_fired_at = ? WHERE id = ? AND last_fired_at = ?"
    }

    fun today(): LocalDate = LocalDate.now(ZONE)

    private data class Locked(val previous: Instant?, val cooldownSec: Int, val dailyLimit: Int?, val firedAt: Instant, val ready: Boolean)

    /**
     * 발동권을 원자적으로 얻는다 — 쿨다운 판정, 하루 슬롯, `last_fired_at` 기록을 규칙 행 잠금 아래 한 트랜잭션에서.
     * 같은 규칙의 동시 평가 중 하나만 [FiringClaim.Claimed]를 받는다(쿨다운이 0이면 하루 한도 안에서 모두).
     * 쿨다운·한도는 엔티티를 읽은 뒤 바뀌었을 수 있어 잠근 행의 값을 쓴다.
     */
    fun claimFiring(ruleId: Long): FiringClaim = tx.execute {
        val row = jdbc.query(LOCK_RULE_SQL.trimIndent(), { rs, _ ->
            Locked(
                previous = rs.getTimestamp("last_fired_at")?.toInstant(),
                cooldownSec = rs.getInt("cooldown_sec"),
                dailyLimit = rs.getObject("daily_limit") as Int?,
                firedAt = rs.getTimestamp("fired_at").toInstant(),
                ready = rs.getBoolean("ready"),
            )
        }, ruleId).firstOrNull() ?: return@execute FiringClaim.Inactive
        if (!row.ready) return@execute FiringClaim.InCooldown(row.cooldownSec)

        val day = today()
        // 새 행(오늘 첫 발동)은 INSERT 경로라 WHERE가 걸리지 않는다 — limit ≥ 1(CHECK)이므로 항상 허용이 맞다.
        // 한도가 없어도 센다(카드의 "오늘 발동" 집계용).
        val slot = jdbc.query(CLAIM_SQL.trimIndent(), { rs, _ -> rs.getInt("executed") }, ruleId, Date.valueOf(day), row.dailyLimit ?: Int.MAX_VALUE)
        if (slot.isEmpty()) return@execute FiringClaim.DailyLimitReached(row.dailyLimit!!)

        jdbc.update(MARK_FIRED_SQL, Timestamp.from(row.firedAt), ruleId)
        FiringClaim.Claimed(ruleId, row.firedAt, row.previous, day)
    }!!

    /**
     * 발동권을 얻었지만 주문이 나가지 않았다(거부·인프라 오류) — 하루 슬롯과 쿨다운을 돌려준다.
     * 쿨다운은 체결된 발동만 센다(예전 EXECUTED 기록 기준과 같은 의미). 잠금 순서는 [claimFiring]과 같다.
     */
    fun releaseFiring(claim: FiringClaim.Claimed) {
        tx.executeWithoutResult {
            // 이전 값이 null일 수 있다 — 타입을 명시해 드라이버가 NULL의 타입을 추측하지 않게 한다.
            val previous = SqlParameterValue(Types.TIMESTAMP, claim.previousFiredAt?.let { Timestamp.from(it) })
            jdbc.update(UNMARK_FIRED_SQL, previous, claim.ruleId, Timestamp.from(claim.firedAt))
            jdbc.update(RELEASE_SQL, claim.ruleId, Date.valueOf(claim.day))
        }
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
