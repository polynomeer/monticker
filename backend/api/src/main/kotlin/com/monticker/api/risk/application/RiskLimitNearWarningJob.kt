package com.monticker.api.risk.application

import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-070 — 리스크 한도 근접 경고. 모의계좌의 VaR·단일 종목 집중도·일일 손실 사용률을 주기적으로 평가해, 사용률이
 * [threshold](기본 80%)를 넘은 규칙을 사용자·규칙·KST 날짜마다 **한 번** 알린다.
 *
 * - 대상은 보유 종목이 있거나 오늘 매도한 사용자뿐이다 — 전 사용자를 매번 훑지 않는다(보유도 거래도 없으면 세 규칙 모두 0이다).
 * - 한 번 보장은 `risk_limit_warnings` PK다. INSERT가 성공한 트랜잭션 안에서만 알림을 발행한다(ADR-065: 같은 트랜잭션 →
 *   아웃박스 → 커밋 후 notify.user). 인스턴스가 여러 대라 같은 사용자를 동시에 평가해도 한 곳만 INSERT에 성공한다.
 * - 실거래 계좌는 평가하지 않는다 — 사용률을 알려면 증권사 잔고 API를 주기적으로 불러야 해 호출 한도를 먹는다.
 * - 리스크 체크를 끈 사용자(유효 한도의 isActive=false)는 건너뛴다.
 */
@Component
class RiskLimitNearWarningJob(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val events: ApplicationEventPublisher,
    private val usage: PaperRiskUsage,
    private val limitService: RiskLimitService,
    private val registry: MeterRegistry,
    @Value("\${app.risk.near-limit.enabled:true}") private val enabled: Boolean = true,
    @Value("\${app.risk.near-limit.threshold:0.8}") private val threshold: Double = 0.8,
) {
    /** 테스트에서 날짜를 고정할 때만 바꾼다. */
    internal var clock: Clock = Clock.systemUTC()
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.risk.near-limit.interval-ms:300000}",
        initialDelayString = "\${app.risk.near-limit.initial-delay-ms:90000}",
    )
    fun run() {
        if (!enabled) return
        val today = LocalDate.now(clock.withZone(KST))
        var notified = 0
        var failed = 0
        for (userId in candidates(today)) {
            try {
                notified += evaluateUser(userId, today)
            } catch (e: Exception) {
                // 한 사용자의 실패(데이터 이상 등)가 나머지 평가를 막지 않는다. 다음 주기에 다시 본다.
                failed++
                log.warn("[RiskNearLimit] 평가 실패 userId={}: {}", userId, e.message)
            }
        }
        if (notified > 0 || failed > 0) log.info("[RiskNearLimit] 경고 {}건, 실패 {}건", notified, failed)
    }

    /** @return 이번에 새로 발행한 알림 수 */
    fun evaluateUser(userId: Long, today: LocalDate): Int {
        val limits = limitService.effective(userId)
        if (!limits.isActive) return 0
        val near = usage.evaluate(userId, limits).filter { it.limit > 0 && it.ratio >= threshold }
        if (near.isEmpty()) return 0

        return tx.execute {
            near.count { u ->
                val usagePct = (u.ratio * 100).coerceAtMost(MAX_USAGE_PCT)
                val inserted = jdbc.update(
                    """INSERT INTO risk_limit_warnings (user_id, rule, kst_date, usage_pct) VALUES (?, ?, ?, ?)
                       ON CONFLICT (user_id, rule, kst_date) DO NOTHING""",
                    userId, u.rule, Date.valueOf(today), usagePct,
                )
                if (inserted == 1) {
                    events.publishEvent(notification(userId, u, usagePct, today))
                    registry.counter("risk_limit_near_warning_total", "rule", u.rule).increment()
                }
                inserted == 1
            }
        } ?: 0
    }

    /** 보유가 있거나 오늘(KST) 매도한 모의계좌 사용자. 탈퇴 사용자는 뺀다. */
    internal fun candidates(today: LocalDate): List<Long> = jdbc.queryForList(
        """SELECT c.user_id FROM (
               SELECT user_id FROM paper_trades GROUP BY user_id, stock_id
               HAVING SUM(CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END) > 0
               UNION
               SELECT user_id FROM paper_trades WHERE side = 'SELL' AND traded_at >= ?
           ) c JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL
           ORDER BY c.user_id""",
        Long::class.java,
        Timestamp.from(today.atStartOfDay(KST).toInstant()),
    )

    /** 지난 경고 기록 정리 — 하루 한 번 보장에는 오늘 행만 필요하다. 일주일은 운영 확인용으로 남긴다. */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    fun purge() {
        val cutoff = LocalDate.now(clock.withZone(KST)).minusDays(RETENTION_DAYS)
        jdbc.update("DELETE FROM risk_limit_warnings WHERE kst_date < ?", Date.valueOf(cutoff))
    }

    private fun notification(userId: Long, u: RuleUsage, usagePct: Double, today: LocalDate): UserNotificationCommand {
        val pct = String.format("%.0f", usagePct)
        val subject = u.subject?.let { " ($it)" } ?: ""
        return UserNotificationCommand(
            userId = userId,
            category = NotificationCategory.RISK_WARNING,
            title = "리스크 한도 ${pct}% 사용 — ${u.label}",
            body = "${u.label}$subject ${fmt(u.current)}%가 한도 ${fmt(u.limit)}%의 ${pct}%에 이르렀습니다. " +
                "한도를 넘으면 위험을 늘리는 매수 주문이 차단됩니다. 리스크 화면에서 확인하세요.",
            dedupKey = "risk-limit-near:$userId:${u.rule}:$today",
            data = mapOf("type" to "RISK_LIMIT_NEAR", "rule" to u.rule, "usagePct" to pct, "path" to "/risk"),
        )
    }

    private fun fmt(v: Double) = String.format("%.2f", v).trimEnd('0').trimEnd('.')

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val RETENTION_DAYS = 7L
        private const val MAX_USAGE_PCT = 999_999.0
    }
}
