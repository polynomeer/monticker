package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.ConditionalOrderStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 상태별 건수. 모든 상태가 키로 있다(없으면 0). [firedThisMonth]는 이번 달(KST) 발동 건수. */
data class ConditionalOrderStats(
    val byStatus: Map<String, Long>,
    val total: Long,
    val firedThisMonth: Long,
    /** [firedThisMonth]를 세기 시작한 시각 — 이번 달 1일 00:00 KST. */
    val monthStart: Instant,
)

/**
 * 발동 기록 한 건 — conditional_orders.triggered_at이 있는 행과, 발동이 낸 증권사 주문의 현재 상태.
 * 별도 로그 테이블이 아니다: 발동은 이미 `ACTIVE → TRIGGERED` 원자적 UPDATE로 시각을 남기고, 결과는 같은 행의 status·
 * executed_order_id·fail_reason이 따라간다. 킬 스위치로 되돌린 발동은 triggered_at이 다시 NULL이 되므로 기록에 없다
 * (주문이 나가지 않았다).
 */
data class ConditionalTriggerEvent(
    val conditionalOrderId: Long,
    val symbol: String,
    val side: String,
    val triggerType: String,
    val triggerPrice: BigDecimal,
    val orderType: String,
    val limitPrice: BigDecimal?,
    val quantity: Int,
    val ocoGroupId: String?,
    val triggeredAt: Instant,
    /** 조건부 주문의 현재 상태(TRIGGERED = 증권사 결과 확인 중, EXECUTED, FAILED). */
    val status: String,
    val failReason: String?,
    val executedOrderId: Long?,
    /** 연결된 증권사 주문 상태. 주문이 없으면(의도 기록 전 실패) null. */
    val orderStatus: String?,
    val filledQty: Int?,
    val avgFillPrice: BigDecimal?,
)

data class ConditionalTriggerPage(
    val content: List<ConditionalTriggerEvent>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 감시 중인 종목의 시세 한 줄. [price]는 최근 1분봉 종가(주문 준비·리밸런싱 추정과 같은 출처) — 표시용이다. */
data class ConditionalOrderQuote(
    val stockId: Long,
    val symbol: String,
    val name: String,
    val price: BigDecimal?,
    val priceAt: Instant?,
)

/**
 * 조건부 주문 화면의 통계·발동 기록·현재가. **읽기 전용** — 증권사를 부르지 않고, 어떤 행도 쓰지 않는다(readOnly 트랜잭션).
 * 모두 인증 주체의 user_id로만 거른다. 입력으로 다른 사용자·임의 종목을 지정할 방법이 없다(시세도 "내 ACTIVE 조건부 주문의
 * 종목"으로만 정해진다).
 */
@Service
@Transactional(readOnly = true)
class ConditionalOrderInsights(private val jdbc: JdbcTemplate) {

    fun stats(userId: Long, now: Instant = Instant.now()): ConditionalOrderStats {
        val counts = jdbc.query(
            "SELECT status, COUNT(*) AS n FROM conditional_orders WHERE user_id = ? GROUP BY status",
            { rs, _ -> rs.getString("status") to rs.getLong("n") },
            userId,
        ).toMap()
        val byStatus = ConditionalOrderStatus.entries.associate { it.name to (counts[it.name] ?: 0L) }
        val monthStart = LocalDate.ofInstant(now, KST).withDayOfMonth(1).atStartOfDay(KST).toInstant()
        val fired = jdbc.queryForObject(
            "SELECT COUNT(*) FROM conditional_orders WHERE user_id = ? AND triggered_at >= ?",
            Long::class.java, userId, Timestamp.from(monthStart),
        ) ?: 0L
        return ConditionalOrderStats(byStatus, byStatus.values.sum(), fired, monthStart)
    }

    fun triggers(userId: Long, page: Int, size: Int): ConditionalTriggerPage {
        val p = page.coerceAtLeast(0)
        val s = size.coerceIn(1, MAX_PAGE_SIZE)
        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM conditional_orders WHERE user_id = ? AND triggered_at IS NOT NULL",
            Long::class.java, userId,
        ) ?: 0L
        val rows = jdbc.query(
            """
            SELECT co.id, co.symbol, co.side, co.trigger_type, co.trigger_price, co.order_type, co.limit_price, co.quantity,
                   co.oco_group_id, co.triggered_at, co.status, co.fail_reason, co.executed_order_id,
                   bo.status AS order_status, bo.filled_qty, bo.avg_fill_price
            FROM conditional_orders co
            LEFT JOIN brokerage_orders bo ON bo.id = co.executed_order_id AND bo.user_id = co.user_id
            WHERE co.user_id = ? AND co.triggered_at IS NOT NULL
            ORDER BY co.triggered_at DESC, co.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            { rs, _ ->
                ConditionalTriggerEvent(
                    conditionalOrderId = rs.getLong("id"),
                    symbol = rs.getString("symbol"),
                    side = rs.getString("side"),
                    triggerType = rs.getString("trigger_type"),
                    triggerPrice = rs.getBigDecimal("trigger_price"),
                    orderType = rs.getString("order_type"),
                    limitPrice = rs.getBigDecimal("limit_price"),
                    quantity = rs.getInt("quantity"),
                    ocoGroupId = rs.getString("oco_group_id"),
                    triggeredAt = rs.getTimestamp("triggered_at").toInstant(),
                    status = rs.getString("status"),
                    failReason = rs.getString("fail_reason"),
                    executedOrderId = rs.getLong("executed_order_id").takeUnless { rs.wasNull() },
                    orderStatus = rs.getString("order_status"),
                    filledQty = rs.getInt("filled_qty").takeUnless { rs.wasNull() },
                    avgFillPrice = rs.getBigDecimal("avg_fill_price"),
                )
            },
            userId, s, p.toLong() * s,
        )
        val pages = if (total == 0L) 0 else ((total + s - 1) / s).toInt()
        return ConditionalTriggerPage(rows, p, s, total, pages)
    }

    /** 내 ACTIVE 조건부 주문 종목의 최근가 — 한 번의 쿼리로. 종목 수는 [MAX_QUOTES]개까지(최근 등록 순). */
    fun quotes(userId: Long): List<ConditionalOrderQuote> =
        jdbc.query(
            """
            SELECT s.id, s.symbol, s.name, c.close, c.candle_time
            FROM (
                SELECT stock_id, MAX(created_at) AS latest FROM conditional_orders
                WHERE user_id = ? AND status = 'ACTIVE' GROUP BY stock_id
                ORDER BY latest DESC LIMIT ?
            ) a
            JOIN stocks s ON s.id = a.stock_id
            LEFT JOIN LATERAL (
                SELECT close, candle_time FROM candles_1m WHERE stock_id = a.stock_id ORDER BY candle_time DESC LIMIT 1
            ) c ON true
            ORDER BY a.latest DESC
            """.trimIndent(),
            { rs, _ ->
                ConditionalOrderQuote(
                    stockId = rs.getLong("id"),
                    symbol = rs.getString("symbol"),
                    name = rs.getString("name"),
                    price = rs.getBigDecimal("close")?.takeIf { it.signum() > 0 },
                    priceAt = rs.getTimestamp("candle_time")?.toInstant(),
                )
            },
            userId, MAX_QUOTES,
        )

    companion object {
        const val MAX_PAGE_SIZE = 50
        const val MAX_QUOTES = 50
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
