package com.monticker.api.matching.application

import com.monticker.api.matching.domain.RiskLimit
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
@Transactional(readOnly = true)
class RiskRuleQueryService(
    private val jdbc: JdbcTemplate,
) {
    fun evaluate(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
        limits: RiskLimit,
    ): List<RuleResult> {
        val checks = mutableListOf<RuleResult>()

        val accountCash = jdbc.queryForObject(
            "SELECT COALESCE(cash, 0) FROM paper_accounts WHERE user_id = ?",
            BigDecimal::class.java, userId,
        ) ?: BigDecimal("10000000")

        // 1. Daily Loss Rule
        val dailyPnl = jdbc.queryForObject(
            """SELECT COALESCE(SUM(CASE WHEN side='SELL' THEN amount ELSE -amount END), 0)
               FROM fills WHERE user_id = ? AND filled_at >= current_date""",
            BigDecimal::class.java, userId,
        ) ?: BigDecimal.ZERO
        val lossLimitAmt = accountCash.multiply(limits.dailyLossLimitPct)
            .divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val dailyLossPassed = dailyPnl >= lossLimitAmt.negate()
        checks.add(RuleResult(
            rule    = "DailyLossRule",
            passed  = dailyLossPassed,
            detail  = "일간 손실 ${dailyPnl.toPlainString()} / 한도 ${lossLimitAmt.negate().toPlainString()}",
            current = dailyPnl.toDouble(),
            limit   = lossLimitAmt.negate().toDouble(),
        ))

        // 2. Concentration Rule (BUY only)
        if (side == "BUY") {
            val holdingRows = jdbc.queryForList(
                """SELECT stock_id, SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) as qty,
                          AVG(CASE WHEN side='BUY' THEN price END) as avg_price
                   FROM paper_trades WHERE user_id = ?
                   GROUP BY stock_id
                   HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
                userId,
            )
            val totalStockValue = holdingRows.sumOf { row ->
                val hStockId = (row["stock_id"] as Number).toLong()
                val hQty     = (row["qty"] as Number).toInt()
                val curPrice = runCatching {
                    jdbc.queryForObject(
                        "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                        BigDecimal::class.java, hStockId,
                    ) ?: BigDecimal.ZERO
                }.getOrDefault(BigDecimal.ZERO)
                curPrice.multiply(BigDecimal(hQty)).toDouble()
            }
            val totalAssets         = accountCash.toDouble() + totalStockValue
            val currentQty          = holdingRows.find { (it["stock_id"] as Number).toLong() == stockId }
                ?.let { (it["qty"] as Number).toInt() } ?: 0
            val currentValue        = estimatedPrice.multiply(BigDecimal(currentQty)).toDouble()
            val newHoldingValue     = currentValue + estimatedPrice.multiply(BigDecimal(qty)).toDouble()
            val concentrationPct    = if (totalAssets > 0) newHoldingValue / totalAssets * 100 else 0.0
            val concentrationLimit  = limits.concentrationLimitPct.toDouble()
            checks.add(RuleResult(
                rule    = "ConcentrationRule",
                passed  = concentrationPct <= concentrationLimit,
                detail  = "집중도 ${String.format("%.2f", concentrationPct)}% / 한도 ${concentrationLimit}%",
                current = concentrationPct,
                limit   = concentrationLimit,
            ))
        }

        // 3. VaR Rule
        val stockIds = jdbc.queryForList(
            "SELECT DISTINCT stock_id FROM paper_trades WHERE user_id = ?",
            Long::class.java, userId,
        )
        val varValue = if (stockIds.isNotEmpty()) {
            val placeholders = stockIds.joinToString(",") { "?" }
            val rows = jdbc.queryForList(
                """SELECT stock_id, close FROM candles_1d WHERE stock_id IN ($placeholders)
                   ORDER BY stock_id, candle_time DESC LIMIT ${stockIds.size * 20}""",
                *stockIds.toTypedArray(),
            )
            val allReturns = rows.groupBy { (it["stock_id"] as Number).toLong() }.values.flatMap { r ->
                r.map { (it["close"] as BigDecimal).toDouble() }
                    .zipWithNext { a, b -> if (b != 0.0) (a - b) / b else 0.0 }
            }
            if (allReturns.size >= 5) {
                val sorted = allReturns.sorted()
                -sorted[(sorted.size * 0.05).toInt().coerceAtLeast(0)] * 100
            } else {
                val mean = if (allReturns.isEmpty()) 0.0 else allReturns.average()
                val std  = if (allReturns.isEmpty()) 0.0 else
                    Math.sqrt(allReturns.sumOf { (it - mean) * (it - mean) } / allReturns.size)
                std * 1.65 * 100
            }
        } else 0.0
        val varLimit = limits.varLimitPct.toDouble()
        checks.add(RuleResult(
            rule    = "VaRRule",
            passed  = varValue <= varLimit,
            detail  = "VaR(95%) ${String.format("%.2f", varValue)}% / 한도 ${varLimit}%",
            current = varValue,
            limit   = varLimit,
        ))

        // 4. Position Count Rule (BUY + new stock only)
        if (side == "BUY") {
            val positionCount = jdbc.queryForObject(
                """SELECT COUNT(DISTINCT stock_id) FROM paper_trades WHERE user_id = ?
                   AND stock_id NOT IN (
                       SELECT DISTINCT pt2.stock_id FROM paper_trades pt2 WHERE pt2.user_id = ?
                       GROUP BY pt2.stock_id
                       HAVING SUM(CASE WHEN pt2.side='BUY' THEN pt2.quantity ELSE -pt2.quantity END) <= 0
                   )""",
                Long::class.java, userId, userId,
            ) ?: 0L
            val isNewStock = jdbc.queryForObject(
                """SELECT COALESCE(SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END), 0)
                   FROM paper_trades WHERE user_id = ? AND stock_id = ?""",
                Int::class.java, userId, stockId,
            ) ?: 0
            if (isNewStock <= 0) {
                val maxPos = limits.maxPositionCount.toDouble()
                checks.add(RuleResult(
                    rule    = "PositionCountRule",
                    passed  = positionCount < maxPos.toLong(),
                    detail  = "보유 종목 ${positionCount}개 / 한도 ${maxPos.toInt()}개",
                    current = positionCount.toDouble(),
                    limit   = maxPos,
                ))
            }
        }

        // 5. Trading Frequency Rule
        val oneHourAgo    = Instant.now().minusSeconds(3600)
        val hourlyOrders  = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE user_id = ? AND created_at > ?",
            Long::class.java, userId, java.sql.Timestamp.from(oneHourAgo),
        ) ?: 0L
        val freqLimit = limits.maxHourlyOrders.toDouble()
        checks.add(RuleResult(
            rule    = "TradingFrequencyRule",
            passed  = hourlyOrders < freqLimit.toLong(),
            detail  = "시간당 주문 ${hourlyOrders}건 / 한도 ${freqLimit.toInt()}건",
            current = hourlyOrders.toDouble(),
            limit   = freqLimit,
        ))

        return checks
    }
}
