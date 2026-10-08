package com.monticker.api.wallet.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant

/** 영수증의 주문 체결 한 건. */
data class ReceiptFill(
    val fillId: Long,
    val quantity: Int,
    val price: BigDecimal,
    val filledAt: Instant,
    /** 이 영수증(거래)을 만든 체결이면 true. */
    val thisTrade: Boolean,
)

/**
 * ADR-096 — 영수증 거래를 만든 주문의 진행 기록(접수 → 예약 잠금 → 체결(부분 포함) → 취소).
 * 정산 단계는 거래별 T+2 정산 기록(`/api/settlement/paper/trade/{id}`)을 그대로 쓴다.
 */
data class ReceiptOrder(
    val orderId: Long,
    val orderType: String,
    val side: String,
    val quantity: Int,
    val filledQty: Int,
    val limitPrice: BigDecimal?,
    val status: String,
    /** 접수 시각 — 사가 진입 시각(V88), 없으면 주문 행 생성 시각. */
    val submittedAt: Instant,
    /** 예약 잠금 시각(V93). 그 이전 주문은 null — 잠금은 있었지만 시각을 기록하지 않았다. */
    val reservedAt: Instant?,
    /** 지정가 BUY가 잠근 현금 = 지정가 × 주문 수량(ADR-074). 그 밖에는 null. */
    val reservedAmount: BigDecimal?,
    /** 지정가 SELL이 잠근 매도 수량(보유 중 팔기로 한 수량). 그 밖에는 null. */
    val reservedQty: Int?,
    /** 취소됐으면 취소 시각(주문 행의 마지막 변경 시각). */
    val cancelledAt: Instant?,
    /** 체결 시점 리스크·보유 수량 부족 등으로 엔진이 취소한 사유. 사용자 취소는 null. */
    val cancelReason: String?,
    val fills: List<ReceiptFill>,
)

/**
 * paper_trades → fills → orders를 SQL 한 문장으로 읽는다(한 스냅샷). 거래와 주문 모두 호출자 소유여야 한다 —
 * 남의 거래면 행이 없어 null이고, 호출자([ReceiptService])는 이미 소유 확인 뒤에만 부른다.
 * ADR-047 이전 거래(fill_id 없음)도 null — 영수증은 예전 3단계로 그린다.
 */
@Component
class ReceiptOrderTimelineQuery(private val jdbc: JdbcTemplate) {

    companion object {
        const val SQL = """
            SELECT o.id AS order_id, o.order_type, o.side, o.quantity, o.filled_qty, o.limit_price, o.status,
                   o.submitted_at, o.created_at, o.reserved_at, o.updated_at, o.reject_reason,
                   f.id AS fill_id, f.quantity AS fill_qty, f.fill_price, f.filled_at,
                   t.fill_id AS this_fill_id
            FROM paper_trades t
            JOIN fills tf ON tf.id = t.fill_id
            JOIN orders o ON o.id = tf.order_id AND o.user_id = t.user_id
            LEFT JOIN fills f ON f.order_id = o.id
            WHERE t.id = ? AND t.user_id = ?
            ORDER BY f.filled_at, f.id
        """
    }

    fun find(userId: Long, tradeId: Long): ReceiptOrder? {
        data class Row(val order: ReceiptOrder, val fill: ReceiptFill?)
        val rows = jdbc.query(SQL.trimIndent(), { rs, _ -> Row(rs.toOrder(), rs.toFill()) }, tradeId, userId)
        val first = rows.firstOrNull() ?: return null
        return first.order.copy(fills = rows.mapNotNull { it.fill })
    }

    private fun ResultSet.toOrder(): ReceiptOrder {
        val side = getString("side")
        val type = getString("order_type")
        val quantity = getInt("quantity")
        val limit = getBigDecimal("limit_price")
        val status = getString("status")
        val isLimit = type == "LIMIT"
        return ReceiptOrder(
            orderId = getLong("order_id"),
            orderType = type,
            side = side,
            quantity = quantity,
            filledQty = getInt("filled_qty"),
            limitPrice = limit,
            status = status,
            submittedAt = (getTimestamp("submitted_at") ?: getTimestamp("created_at")).toInstant(),
            reservedAt = getTimestamp("reserved_at")?.toInstant(),
            reservedAmount = if (isLimit && side == "BUY" && limit != null) limit.multiply(BigDecimal(quantity)) else null,
            reservedQty = if (isLimit && side == "SELL") quantity else null,
            cancelledAt = if (status == "CANCELLED") getTimestamp("updated_at")?.toInstant() else null,
            cancelReason = getString("reject_reason"),
            fills = emptyList(),
        )
    }

    private fun ResultSet.toFill(): ReceiptFill? {
        val id = getLong("fill_id")
        if (wasNull()) return null
        return ReceiptFill(
            fillId = id,
            quantity = getInt("fill_qty"),
            price = getBigDecimal("fill_price"),
            filledAt = getTimestamp("filled_at").toInstant(),
            thisTrade = id == getLong("this_fill_id"),
        )
    }
}
