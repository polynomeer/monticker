package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.common.exception.TradingHaltedException
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class HaltScope { GLOBAL, PROVIDER, USER }

data class TradingHalt(
    val id: Long,
    val scope: HaltScope,
    val target: String?,
    val reason: String,
    val haltedBy: Long?,
    val haltedAt: Instant,
    val liftedBy: Long?,
    val liftedAt: Instant?,
    val liftReason: String?,
) {
    /**
     * 사용자에게 보여줄 문구. 사용자 범위 스위치는 사유를 숨긴다 — 자격증명 유출 의심 같은 내부 판단을 노출하지 않는다.
     */
    val userMessage: String get() = when (scope) {
        HaltScope.USER -> "계정의 실거래 주문이 제한되었습니다. 고객센터로 문의해주세요."
        HaltScope.PROVIDER -> "${target} 실거래 주문이 일시 중단되었습니다: $reason"
        HaltScope.GLOBAL -> "실거래 주문이 일시 중단되었습니다: $reason"
    }
}

/**
 * ADR-057 — 실거래 주문 킬 스위치. 캐시하지 않는다: 켜는 순간 다음 주문부터 막혀야 하고, SQL로 직접 켜도 효력이 있어야 한다.
 * 판정 쿼리는 활성 행만 담은 부분 인덱스를 탄다.
 */
@Service
class TradingHaltService(
    private val jdbc: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 게이지는 스크레이프 때 DB를 조회하지 않는다 — DB 장애 중엔 범위마다 커넥션 타임아웃(3s)을 기다려 스크레이프 전체가
    // 시간 초과로 사라지고, 풀이 포화되면 주문 경로와 커넥션을 다툰다. 주기 갱신 값을 읽는다(BrokerageOrderReconciler와 같은 방식).
    private val activeCounts = HaltScope.entries.associateWith { AtomicLong(0) }

    init {
        activeCounts.forEach { (scope, count) ->
            Gauge.builder("trading_halt_active", count) { it.get().toDouble() }
                .tag("scope", scope.name)
                .description("활성 킬 스위치 수 (ADR-057)")
                .register(meterRegistry)
        }
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    fun refreshGauges() {
        val counts = jdbc.query(
            "SELECT scope, COUNT(*) AS n FROM trading_halts WHERE lifted_at IS NULL GROUP BY scope",
            { rs, _ -> HaltScope.valueOf(rs.getString("scope")) to rs.getLong("n") },
        ).toMap()
        activeCounts.forEach { (scope, count) -> count.set(counts[scope] ?: 0L) }
    }

    /** [provider] 계좌로 [userId]가 내는 실주문을 막는 활성 스위치. 여러 개면 가장 넓은 범위(GLOBAL > PROVIDER > USER). */
    fun findActive(provider: BrokerageProvider, userId: Long): TradingHalt? =
        jdbc.query(
            """
            SELECT * FROM trading_halts
            WHERE lifted_at IS NULL
              AND (scope = 'GLOBAL' OR (scope = 'PROVIDER' AND target = ?) OR (scope = 'USER' AND target = ?))
            ORDER BY CASE scope WHEN 'GLOBAL' THEN 0 WHEN 'PROVIDER' THEN 1 ELSE 2 END
            LIMIT 1
            """.trimIndent(),
            ::mapRow, provider.name, userId.toString(),
        ).firstOrNull()

    fun halt(scope: HaltScope, target: String?, reason: String, adminId: Long?): TradingHalt {
        require(reason.isNotBlank()) { "사유를 입력해주세요." }
        val normalized = when (scope) {
            HaltScope.GLOBAL -> { require(target.isNullOrBlank()) { "전역 스위치에는 대상을 지정하지 않습니다." }; null }
            HaltScope.PROVIDER -> target?.uppercase()?.also {
                require(it == BrokerageProvider.KIS.name || it == BrokerageProvider.TOSS.name) { "증권사는 KIS 또는 TOSS입니다: $target" }
            } ?: throw IllegalArgumentException("증권사를 지정해주세요.")
            HaltScope.USER -> target?.toLongOrNull()?.also { id ->
                require(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Long::class.java, id)!! > 0) { "사용자가 없습니다: $id" }
            }?.toString() ?: throw IllegalArgumentException("사용자 ID를 지정해주세요.")
        }
        val id = try {
            jdbc.queryForObject(
                "INSERT INTO trading_halts (scope, target, reason, halted_by) VALUES (?, ?, ?, ?) RETURNING id",
                Long::class.java, scope.name, normalized, reason.trim(), adminId,
            )!!
        } catch (e: DuplicateKeyException) {
            throw BusinessRuleException("이미 켜져 있는 스위치입니다: $scope ${normalized ?: ""}".trim())
        }
        log.warn("[KillSwitch] 실거래 주문 정지 ON: id={} scope={} target={} by={} reason={}", id, scope, normalized, adminId, reason)
        runCatching { refreshGauges() }
        return get(id)
    }

    fun lift(id: Long, reason: String, adminId: Long?): TradingHalt {
        require(reason.isNotBlank()) { "해제 사유를 입력해주세요." }
        val updated = jdbc.update(
            "UPDATE trading_halts SET lifted_at = now(), lifted_by = ?, lift_reason = ? WHERE id = ? AND lifted_at IS NULL",
            adminId, reason.trim(), id,
        )
        if (updated == 0) throw BusinessRuleException("활성 스위치가 아닙니다: #$id")
        log.warn("[KillSwitch] 실거래 주문 정지 OFF: id={} by={} reason={}", id, adminId, reason)
        runCatching { refreshGauges() }
        return get(id)
    }

    fun list(activeOnly: Boolean, limit: Int = 100): List<TradingHalt> =
        jdbc.query(
            "SELECT * FROM trading_halts ${if (activeOnly) "WHERE lifted_at IS NULL" else ""} ORDER BY halted_at DESC LIMIT ?",
            ::mapRow, limit,
        )

    private fun get(id: Long): TradingHalt =
        jdbc.query("SELECT * FROM trading_halts WHERE id = ?", ::mapRow, id).firstOrNull()
            ?: throw NoSuchElementException("스위치 없음: #$id")

    @Suppress("UNUSED_PARAMETER")
    private fun mapRow(rs: ResultSet, rowNum: Int) = TradingHalt(
        id         = rs.getLong("id"),
        scope      = HaltScope.valueOf(rs.getString("scope")),
        target     = rs.getString("target"),
        reason     = rs.getString("reason"),
        haltedBy   = rs.getObject("halted_by") as Long?,
        haltedAt   = rs.getTimestamp("halted_at").toInstant(),
        liftedBy   = rs.getObject("lifted_by") as Long?,
        liftedAt   = rs.getTimestamp("lifted_at")?.toInstant(),
        liftReason = rs.getString("lift_reason"),
    )
}

fun TradingHalt.toException() = TradingHaltedException(userMessage, scope.name)
