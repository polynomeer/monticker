package com.monticker.api.paper.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * CQRS 읽기모델 프로젝션.
 *
 * PaperTrade INSERT 직후 호출되어 portfolio_positions 테이블을 동기 업데이트한다.
 * 조회 측(PaperPortfolioQueryService, WalletService)은 paper_trades 집계 대신 이 테이블을 읽는다.
 *
 * BUY:  net_qty += qty, total_cost += amount, avg_buy_price = total_cost / net_qty
 * SELL: net_qty -= qty, total_cost -= (avg_buy_price × qty), net_qty = 0 이면 행 삭제
 */
@Component
class PortfolioPositionProjection(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun onBuy(userId: Long, stockId: Long, quantity: Int, amount: BigDecimal) {
        jdbc.update(
            """
            INSERT INTO portfolio_positions (user_id, stock_id, net_qty, avg_buy_price, total_cost, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (user_id, stock_id) DO UPDATE
              SET net_qty       = portfolio_positions.net_qty + EXCLUDED.net_qty,
                  total_cost    = portfolio_positions.total_cost + EXCLUDED.total_cost,
                  avg_buy_price = (portfolio_positions.total_cost + EXCLUDED.total_cost)
                                  / (portfolio_positions.net_qty + EXCLUDED.net_qty),
                  updated_at    = now()
            """.trimIndent(),
            userId, stockId, quantity,
            if (quantity > 0) amount.divide(BigDecimal(quantity), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO,
            amount,
        )
        log.debug("[CQRS:buy] userId={} stockId={} qty={} amount={}", userId, stockId, quantity, amount)
    }

    @Transactional
    fun onSell(userId: Long, stockId: Long, quantity: Int) {
        val current = jdbc.queryForMap(
            "SELECT net_qty, avg_buy_price FROM portfolio_positions WHERE user_id = ? AND stock_id = ?",
            userId, stockId
        ).takeIf { it.isNotEmpty() } ?: return

        val netQty     = (current["net_qty"] as Number).toInt()
        val avgPrice   = current["avg_buy_price"] as? BigDecimal ?: BigDecimal.ZERO
        val newQty     = (netQty - quantity).coerceAtLeast(0)
        val costRelief = avgPrice.multiply(BigDecimal(quantity))

        if (newQty == 0) {
            jdbc.update(
                "DELETE FROM portfolio_positions WHERE user_id = ? AND stock_id = ?",
                userId, stockId,
            )
        } else {
            jdbc.update(
                """
                UPDATE portfolio_positions
                   SET net_qty    = ?,
                       total_cost = GREATEST(total_cost - ?, 0),
                       updated_at = now()
                 WHERE user_id = ? AND stock_id = ?
                """.trimIndent(),
                newQty, costRelief, userId, stockId,
            )
        }
        log.debug("[CQRS:sell] userId={} stockId={} qty={} newQty={}", userId, stockId, quantity, newQty)
    }

    @Transactional
    fun onReset(userId: Long) {
        val deleted = jdbc.update("DELETE FROM portfolio_positions WHERE user_id = ?", userId)
        log.info("[CQRS:reset] userId={} — {}개 포지션 삭제", userId, deleted)
    }
}
