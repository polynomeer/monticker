package com.monticker.api.paper.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** ADR-075 — 발동 조건. SELL 익절/손절과 양방향 가격 도달. */
enum class PaperTriggerType {
    STOP_LOSS, TAKE_PROFIT, PRICE_ABOVE, PRICE_BELOW;

    fun isTriggered(price: BigDecimal, trigger: BigDecimal): Boolean = when (this) {
        TAKE_PROFIT, PRICE_ABOVE -> price >= trigger
        STOP_LOSS, PRICE_BELOW -> price <= trigger
    }

    /** 익절·손절은 보유 종목을 파는 주문이다 — BUY로 걸면 의미가 뒤집힌다. */
    fun allows(side: String): Boolean = when (this) {
        STOP_LOSS, TAKE_PROFIT -> side == "SELL"
        PRICE_ABOVE, PRICE_BELOW -> side == "BUY" || side == "SELL"
    }
}

data class PaperConditionalLeg(val triggerType: String, val triggerPrice: BigDecimal)

/** legs 1개 = 단일 조건, 2개 = OCO(한쪽이 체결되면 다른 쪽 취소). */
data class PaperConditionalRequest(
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val legs: List<PaperConditionalLeg>,
)

data class PaperConditionalOrderResponse(
    val id: Long,
    val stockId: Long,
    val side: String,
    val triggerType: String,
    val triggerPrice: BigDecimal,
    val quantity: Int,
    val ocoGroupId: UUID?,
    val parentOrderId: Long?,
    val status: String,
    val failReason: String?,
    val executedOrderId: Long?,
    val createdAt: Instant,
    val triggeredAt: Instant?,
)

/**
 * ADR-075 — 모의투자 조건부 주문의 생성·조회·취소와 부모 주문 연동("체결 시 자동 등록").
 * 발동은 [PaperConditionalOrderTrigger]가 한다. 이 테이블에서 실브로커로 가는 경로는 없다 — 발동은 matching::submit뿐이다.
 */
@Service
@Transactional
class PaperConditionalOrderService(private val jdbc: JdbcTemplate) {

    companion object {
        /** 사용자당 살아 있는(ACTIVE·WAITING_PARENT) 조건부 주문 상한 — 스위퍼 비용과 실수 방지. */
        const val MAX_LIVE_PER_USER = 50

        private const val COLUMNS = """id, stock_id, side, trigger_type, trigger_price, quantity, oco_group_id, parent_order_id,
            status, fail_reason, executed_order_id, created_at, triggered_at"""

        val MAPPER = RowMapper { rs, _ ->
            PaperConditionalOrderResponse(
                id = rs.getLong("id"), stockId = rs.getLong("stock_id"), side = rs.getString("side"),
                triggerType = rs.getString("trigger_type"), triggerPrice = rs.getBigDecimal("trigger_price"),
                quantity = rs.getInt("quantity"), ocoGroupId = rs.getObject("oco_group_id", UUID::class.java),
                parentOrderId = rs.getObject("parent_order_id") as Long?, status = rs.getString("status"),
                failReason = rs.getString("fail_reason"), executedOrderId = rs.getObject("executed_order_id") as Long?,
                createdAt = rs.getTimestamp("created_at").toInstant(), triggeredAt = rs.getTimestamp("triggered_at")?.toInstant(),
            )
        }
    }

    fun create(userId: Long, req: PaperConditionalRequest): List<PaperConditionalOrderResponse> {
        validate(req)
        if (req.side == "SELL") {
            // 등록 시점에 보유가 있어야 한다(발동 시점에도 사가가 다시 확인한다 — 그 사이 팔았으면 FAILED).
            val held = jdbc.query(
                "SELECT net_qty FROM portfolio_positions WHERE user_id = ? AND stock_id = ?",
                { rs, _ -> rs.getInt("net_qty") }, userId, req.stockId,
            ).firstOrNull() ?: 0
            require(held >= req.quantity) { "보유 수량 부족: 보유 $held, 조건부 매도 ${req.quantity}" }
        }
        return insertLegs(userId, req, parentOrderId = null, status = "ACTIVE")
    }

    /**
     * 주문 패널의 "체결 시 자동 등록" — 매수 주문에 익절·손절을 붙인다. [parentFilled]면 바로 ACTIVE,
     * 아니면(미체결 지정가) WAITING_PARENT로 두었다가 [onParentFilled]가 깨운다.
     */
    fun attachBracket(
        userId: Long, stockId: Long, quantity: Int, parentOrderId: Long, parentFilled: Boolean,
        takeProfitPrice: BigDecimal?, stopLossPrice: BigDecimal?,
    ): List<PaperConditionalOrderResponse> {
        val legs = buildList {
            takeProfitPrice?.let { add(PaperConditionalLeg("TAKE_PROFIT", it)) }
            stopLossPrice?.let { add(PaperConditionalLeg("STOP_LOSS", it)) }
        }
        if (legs.isEmpty()) return emptyList()
        val req = PaperConditionalRequest(stockId, "SELL", quantity, legs)
        validate(req)
        return insertLegs(userId, req, parentOrderId, if (parentFilled) "ACTIVE" else "WAITING_PARENT")
    }

    /** paper.PaperExecutionListener가 체결과 같은 트랜잭션에서 부른다. */
    fun onParentFilled(orderId: Long): Int = jdbc.update(
        "UPDATE paper_conditional_orders SET status = 'ACTIVE', updated_at = now() WHERE parent_order_id = ? AND status = 'WAITING_PARENT'",
        orderId,
    )

    fun onParentCancelled(orderId: Long): Int = jdbc.update(
        "UPDATE paper_conditional_orders SET status = 'CANCELLED', fail_reason = '부모 주문 취소', updated_at = now() " +
            "WHERE parent_order_id = ? AND status = 'WAITING_PARENT'",
        orderId,
    )

    /** 계좌 초기화 — 보유가 사라지므로 살아 있는 조건부 주문은 모두 무의미하다. */
    fun cancelAllLive(userId: Long): Int = jdbc.update(
        "UPDATE paper_conditional_orders SET status = 'CANCELLED', fail_reason = '계좌 초기화', updated_at = now() " +
            "WHERE user_id = ? AND status IN ('ACTIVE', 'WAITING_PARENT')",
        userId,
    )

    fun cancel(userId: Long, id: Long): PaperConditionalOrderResponse {
        // 발동과 경합하면 행 락이 직렬화한다 — 발동기가 먼저 잡았으면 상태가 바뀌어 아래 UPDATE가 0건이다.
        val updated = jdbc.update(
            "UPDATE paper_conditional_orders SET status = 'CANCELLED', updated_at = now() " +
                "WHERE id = ? AND user_id = ? AND status IN ('ACTIVE', 'WAITING_PARENT')",
            id, userId,
        )
        val row = find(userId, id) ?: throw NoSuchElementException("조건부 주문 없음: $id")
        check(updated > 0) { "취소 불가 상태: ${row.status}" }
        return row
    }

    @Transactional(readOnly = true)
    fun list(userId: Long, stockId: Long?): List<PaperConditionalOrderResponse> =
        if (stockId != null) jdbc.query(
            "SELECT $COLUMNS FROM paper_conditional_orders WHERE user_id = ? AND stock_id = ? ORDER BY created_at DESC LIMIT 100",
            MAPPER, userId, stockId,
        ) else jdbc.query(
            "SELECT $COLUMNS FROM paper_conditional_orders WHERE user_id = ? ORDER BY created_at DESC LIMIT 100",
            MAPPER, userId,
        )

    private fun find(userId: Long, id: Long) = jdbc.query(
        "SELECT $COLUMNS FROM paper_conditional_orders WHERE id = ? AND user_id = ?", MAPPER, id, userId,
    ).firstOrNull()

    private fun validate(req: PaperConditionalRequest) {
        require(req.side == "BUY" || req.side == "SELL") { "side는 BUY 또는 SELL이어야 합니다" }
        require(req.quantity > 0) { "수량은 1 이상이어야 합니다" }
        require(req.legs.size in 1..2) { "조건은 1개(단일) 또는 2개(OCO)여야 합니다" }
        val types = req.legs.map { leg ->
            val t = runCatching { PaperTriggerType.valueOf(leg.triggerType) }.getOrNull()
                ?: throw IllegalArgumentException("알 수 없는 조건: ${leg.triggerType}")
            require(t.allows(req.side)) { "${leg.triggerType}는 ${req.side} 주문에 쓸 수 없습니다" }
            require(leg.triggerPrice > BigDecimal.ZERO) { "발동 가격은 0보다 커야 합니다" }
            require(leg.triggerPrice.stripTrailingZeros().scale() <= 4) { "발동 가격은 소수점 4자리까지입니다" }
            t to leg.triggerPrice
        }
        if (types.size == 2) {
            require(types[0].first != types[1].first) { "OCO는 서로 다른 두 조건이어야 합니다" }
            val tp = types.firstOrNull { it.first == PaperTriggerType.TAKE_PROFIT }?.second
            val sl = types.firstOrNull { it.first == PaperTriggerType.STOP_LOSS }?.second
            if (tp != null && sl != null) require(tp > sl) { "익절가는 손절가보다 높아야 합니다" }
        }
    }

    private fun insertLegs(userId: Long, req: PaperConditionalRequest, parentOrderId: Long?, status: String): List<PaperConditionalOrderResponse> {
        // 상한 확인과 INSERT 사이에 같은 사용자의 동시 등록이 끼어들지 않게 사용자 단위 트랜잭션 락을 잡는다.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('paper_cond:' || ?::text))", { _, _ -> 0 }, userId)
        val live = jdbc.queryForObject(
            "SELECT count(*) FROM paper_conditional_orders WHERE user_id = ? AND status IN ('ACTIVE', 'WAITING_PARENT')",
            Long::class.java, userId,
        ) ?: 0L
        require(live + req.legs.size <= MAX_LIVE_PER_USER) { "살아 있는 조건부 주문은 최대 ${MAX_LIVE_PER_USER}건입니다" }

        val group = if (req.legs.size == 2) UUID.randomUUID() else null
        val now = Timestamp.from(Instant.now())
        val ids = req.legs.map { leg ->
            jdbc.queryForObject(
                """
                INSERT INTO paper_conditional_orders
                    (user_id, stock_id, side, trigger_type, trigger_price, quantity, oco_group_id, parent_order_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """.trimIndent(),
                Long::class.java,
                userId, req.stockId, req.side, leg.triggerType, leg.triggerPrice, req.quantity, group, parentOrderId, status, now, now,
            )!!
        }
        return ids.mapNotNull { find(userId, it) }
    }
}
