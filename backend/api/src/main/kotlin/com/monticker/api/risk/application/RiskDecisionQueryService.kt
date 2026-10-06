package com.monticker.api.risk.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** 리스크 게이트가 막은 주문 한 건(risk_check_logs). [detail]은 막은 규칙의 판정 사유. */
data class RiskDecision(
    val id: Long,
    val createdAt: Instant,
    val accountType: String,
    val stockId: Long?,
    val symbol: String?,
    val stockName: String?,
    val side: String?,
    val quantity: Int?,
    val blockedBy: String?,
    val detail: String?,
)

data class RiskDecisionPage(val items: List<RiskDecision>, val page: Int, val size: Int, val hasNext: Boolean)

data class RiskDecisionSummary(val blockedThisMonth: Long, val monthStart: Instant)

/**
 * 리스크 게이트 판정 이력 조회 — 본인 것만. 판정 기록은 이미 RiskCheckAuditLogger가 모든 판정(모의·실거래)을 남기고 있다.
 * 화면("차단·경고 기록")은 막힌 판정만 보여 준다. 설정 화면의 사전 점검(`dry_run`)은 주문이 아니므로 목록·집계에서 뺀다. 승인 로그는 주문마다 쌓여 대부분을 차지하고, 경고(통과하되 알림) 모드는 아직 없다.
 */
@Service
@Transactional(readOnly = true)
class RiskDecisionQueryService(private val jdbc: JdbcTemplate) {
    internal var clock: Clock = Clock.systemUTC()

    fun blocked(userId: Long, page: Int, size: Int): RiskDecisionPage {
        require(page in 0..MAX_PAGE) { "page는 0 이상 $MAX_PAGE 이하여야 합니다." }
        require(size in 1..MAX_SIZE) { "size는 1 이상 $MAX_SIZE 이하여야 합니다." }
        // size + 1개를 읽어 다음 페이지 유무만 안다 — 전체 COUNT는 하지 않는다.
        val rows = jdbc.query(
            """SELECT l.id, l.created_at, l.account_type, l.stock_id, s.symbol, s.name, l.side, l.quantity, l.blocked_by,
                      (SELECT c->>'detail' FROM jsonb_array_elements(l.checks_json) c WHERE c->>'rule' = l.blocked_by LIMIT 1) AS detail
               FROM risk_check_logs l LEFT JOIN stocks s ON s.id = l.stock_id
               WHERE l.user_id = ? AND l.approved = false AND l.dry_run = false
               ORDER BY l.created_at DESC, l.id DESC
               LIMIT ? OFFSET ?""",
            { rs, _ ->
                RiskDecision(
                    id = rs.getLong("id"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    accountType = rs.getString("account_type"),
                    stockId = rs.getObject("stock_id")?.let { (it as Number).toLong() },
                    symbol = rs.getString("symbol"),
                    stockName = rs.getString("name"),
                    side = rs.getString("side"),
                    quantity = rs.getObject("quantity")?.let { (it as Number).toInt() },
                    blockedBy = rs.getString("blocked_by"),
                    detail = rs.getString("detail"),
                )
            },
            userId, size + 1, page * size,
        )
        return RiskDecisionPage(rows.take(size), page, size, hasNext = rows.size > size)
    }

    /** 이번 달(KST) 차단 건수 — 모의·실거래 합산. */
    fun summary(userId: Long): RiskDecisionSummary {
        val monthStart = clock.instant().atZone(KST).toLocalDate().withDayOfMonth(1).atStartOfDay(KST).toInstant()
        val count = jdbc.query(
            "SELECT COUNT(*) FROM risk_check_logs WHERE user_id = ? AND approved = false AND dry_run = false AND created_at >= ?",
            { rs, _ -> rs.getLong(1) },
            userId, Timestamp.from(monthStart),
        ).firstOrNull() ?: 0L
        return RiskDecisionSummary(count, monthStart)
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        const val MAX_SIZE = 50
        const val MAX_PAGE = 200
    }
}
