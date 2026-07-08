package com.monticker.api.matching.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * CQRS Read Side — Fill 조회 전용 서비스.
 *
 * userId 기반 체결 내역 조회는 Aggregate 경계(Order → Fill)를 우회하므로
 * Write Side(FillRepository)와 분리하여 JdbcTemplate 쿼리로 처리한다.
 * FillRepository.findAllByUserIdOrderByFilledAtDesc()는 이 서비스로 대체되며,
 * FillRepository는 Order Aggregate 내부 접근(findAllByOrderId)만 담당한다.
 */
@Service
@Transactional(readOnly = true)
class FillQueryService(private val jdbc: JdbcTemplate) {

    fun findByUserId(userId: Long): List<FillDto> =
        jdbc.query(
            """
            SELECT f.id, f.order_id, f.stock_id, f.side, f.quantity,
                   f.fill_price, f.amount, f.fee, f.filled_at
            FROM fills f
            WHERE f.user_id = ?
            ORDER BY f.filled_at DESC
            """,
            { rs, _ ->
                FillDto(
                    id        = rs.getLong("id"),
                    orderId   = rs.getLong("order_id"),
                    stockId   = rs.getLong("stock_id"),
                    side      = rs.getString("side"),
                    quantity  = rs.getInt("quantity"),
                    fillPrice = rs.getBigDecimal("fill_price"),
                    amount    = rs.getBigDecimal("amount"),
                    fee       = rs.getBigDecimal("fee"),
                    filledAt  = rs.getTimestamp("filled_at").toInstant(),
                )
            },
            userId,
        )

    fun findByOrderId(orderId: Long, userId: Long): List<FillDto> =
        jdbc.query(
            """
            SELECT f.id, f.order_id, f.stock_id, f.side, f.quantity,
                   f.fill_price, f.amount, f.fee, f.filled_at
            FROM fills f
            JOIN orders o ON o.id = f.order_id
            WHERE f.order_id = ? AND o.user_id = ?
            ORDER BY f.filled_at ASC
            """,
            { rs, _ ->
                FillDto(
                    id        = rs.getLong("id"),
                    orderId   = rs.getLong("order_id"),
                    stockId   = rs.getLong("stock_id"),
                    side      = rs.getString("side"),
                    quantity  = rs.getInt("quantity"),
                    fillPrice = rs.getBigDecimal("fill_price"),
                    amount    = rs.getBigDecimal("amount"),
                    fee       = rs.getBigDecimal("fee"),
                    filledAt  = rs.getTimestamp("filled_at").toInstant(),
                )
            },
            orderId, userId,
        )
}
