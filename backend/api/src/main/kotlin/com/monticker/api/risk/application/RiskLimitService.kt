package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.risk.domain.RiskLimitField
import com.monticker.api.risk.infrastructure.RiskLimitRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 대기 중인 한도 완화. [field]는 API 필드 이름(RiskLimitField.key), [value]가 null이면 섹터 한도 해제. */
@NamedInterface("api")
data class PendingLimitChange(
    val field: String,
    val value: BigDecimal?,
    val requestedAt: Instant,
    val effectiveAt: Instant,
)

@NamedInterface("api")
data class RiskLimitsView(
    val limits: RiskLimit,
    val pending: List<PendingLimitChange>,
    val coolingOffHours: Long = RiskLimitService.COOLING_OFF.toHours(),
)

/**
 * ADR-069 — 리스크 한도의 유일한 쓰기 경로와 "유효 한도" 계산.
 *
 * - **강화는 즉시, 완화는 24시간 뒤.** 완화 = 한도를 올리기, 섹터 한도 해제, 리스크 체크 끄기([RiskLimitField.looseness]).
 *   손실 직후 감정적으로 한도를 풀고 바로 더 큰 주문을 내는 것을 막는다. 같은 한도가 실거래 게이트에도 쓰인다.
 * - 완화 요청은 `risk_limit_pending_changes`에 항목당 하나 둔다. 더 느슨한 값으로 바꾸면 시계가 다시 24시간이 되고,
 *   덜 느슨한 값으로 줄이면 기존 적용 시각을 유지한다(더 느슨한 값이 그 시각에 적용될 예정이었으므로 안전하다).
 *   현재 값과 같은 값을 보내면 그 항목의 대기 변경을 취소한다.
 * - 판정은 [effective]를 쓴다 — 적용 시각이 지난 대기 변경을 메모리에서 얹은 사본. 판정 경로에서는 쓰지 않는다(행 잠금 없음).
 *   DB 반영(승격)은 조회·수정 때 행 잠금 아래에서 한다. 승격 전후 어느 시점에 읽어도 유효 한도는 같거나 더 엄격하다.
 */
@NamedInterface("api")
@Service
class RiskLimitService(
    private val repo: RiskLimitRepository,
    private val jdbc: JdbcTemplate,
) {
    /** 테스트에서 시각을 고정할 때만 바꾼다. */
    internal var clock: Clock = Clock.systemUTC()

    /** 판정에 쓸 유효 한도(영속되지 않는 사본). 행이 없으면 기본값. */
    @Transactional(readOnly = true)
    fun effective(userId: Long): RiskLimit {
        val copy = copyOf(repo.findByUserId(userId).orElseGet { RiskLimit(userId = userId) })
        due(userId, clock.instant()).forEach { (f, v) -> f.set(copy, v) }
        return copy
    }

    /** 화면 조회 — 기본값 행이 없으면 만들고, 적용 시각이 지난 변경이 있으면 DB에 반영한 뒤 돌려준다. */
    @Transactional
    fun view(userId: Long): RiskLimitsView {
        val now = clock.instant()
        val limits = if (due(userId, now).isEmpty()) {
            repo.findByUserId(userId).orElseGet { lockOrCreate(userId) }
        } else {
            lockOrCreate(userId).also { promote(it, now) }
        }
        return RiskLimitsView(limits, pending(userId))
    }

    /**
     * 한도 변경. [changes]에 없는 항목은 그대로다. 값 검증은 저장 전에 모두 한다(하나라도 틀리면 아무것도 바꾸지 않는다).
     * 같은 사용자의 동시 수정은 risk_limits 행 잠금으로 직렬화한다.
     */
    @Transactional
    fun update(userId: Long, changes: Map<RiskLimitField, BigDecimal?>): RiskLimitsView {
        changes.forEach { (f, v) -> f.validate(v) }
        val now = clock.instant()
        val limits = lockOrCreate(userId)
        promote(limits, now)
        val existing = pending(userId).associateBy { RiskLimitField.ofKey(it.field)!! }

        for ((f, v) in changes) {
            val cmp = f.looseness(v).compareTo(f.looseness(f.get(limits)))
            when {
                cmp == 0 -> deletePending(userId, f)
                cmp < 0 -> { f.set(limits, v); deletePending(userId, f) }
                else -> {
                    val prev = existing[f]
                    val keepClock = prev != null && f.looseness(v) <= f.looseness(prev.value)
                    upsertPending(
                        userId, f, v,
                        requestedAt = if (keepClock) prev!!.requestedAt else now,
                        effectiveAt = if (keepClock) prev!!.effectiveAt else now.plus(COOLING_OFF),
                    )
                }
            }
        }
        limits.updatedAt = now
        return RiskLimitsView(repo.save(limits), pending(userId))
    }

    private fun lockOrCreate(userId: Long): RiskLimit {
        // 동시 생성 경합은 ON CONFLICT로 흡수한다 — JPA save()는 UNIQUE(user_id) 위반으로 터진다.
        jdbc.update("INSERT INTO risk_limits (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId)
        return repo.findForUpdate(userId).orElseThrow()
    }

    private fun promote(limits: RiskLimit, now: Instant) {
        val due = due(limits.userId, now)
        if (due.isEmpty()) return
        due.forEach { (f, v) -> f.set(limits, v) }
        limits.updatedAt = now
        repo.save(limits)
        jdbc.update("DELETE FROM risk_limit_pending_changes WHERE user_id = ? AND effective_at <= ?", limits.userId, Timestamp.from(now))
    }

    private fun due(userId: Long, now: Instant): List<Pair<RiskLimitField, BigDecimal?>> = jdbc.query(
        "SELECT field, new_value FROM risk_limit_pending_changes WHERE user_id = ? AND effective_at <= ?",
        { rs, _ -> RiskLimitField.valueOf(rs.getString("field")) to rs.getBigDecimal("new_value") },
        userId, Timestamp.from(now),
    )

    private fun pending(userId: Long): List<PendingLimitChange> = jdbc.query(
        """SELECT field, new_value, requested_at, effective_at FROM risk_limit_pending_changes
           WHERE user_id = ? ORDER BY effective_at""",
        { rs, _ ->
            PendingLimitChange(
                field = RiskLimitField.valueOf(rs.getString("field")).key,
                value = rs.getBigDecimal("new_value"),
                requestedAt = rs.getTimestamp("requested_at").toInstant(),
                effectiveAt = rs.getTimestamp("effective_at").toInstant(),
            )
        },
        userId,
    )

    private fun deletePending(userId: Long, f: RiskLimitField) {
        jdbc.update("DELETE FROM risk_limit_pending_changes WHERE user_id = ? AND field = ?", userId, f.name)
    }

    private fun upsertPending(userId: Long, f: RiskLimitField, v: BigDecimal?, requestedAt: Instant, effectiveAt: Instant) {
        jdbc.update(
            """INSERT INTO risk_limit_pending_changes (user_id, field, new_value, requested_at, effective_at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (user_id, field) DO UPDATE
               SET new_value = EXCLUDED.new_value, requested_at = EXCLUDED.requested_at, effective_at = EXCLUDED.effective_at""",
            userId, f.name, v, Timestamp.from(requestedAt), Timestamp.from(effectiveAt),
        )
    }

    private fun copyOf(l: RiskLimit) = RiskLimit(
        userId = l.userId,
        dailyLossLimitPct = l.dailyLossLimitPct,
        concentrationLimitPct = l.concentrationLimitPct,
        varLimitPct = l.varLimitPct,
        maxPositionCount = l.maxPositionCount,
        maxHourlyOrders = l.maxHourlyOrders,
        sectorConcentrationLimitPct = l.sectorConcentrationLimitPct,
        isActive = l.isActive,
        updatedAt = l.updatedAt,
    )

    companion object {
        val COOLING_OFF: Duration = Duration.ofHours(24)
    }
}
