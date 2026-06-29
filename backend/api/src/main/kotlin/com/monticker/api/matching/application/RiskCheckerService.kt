package com.monticker.api.matching.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.infrastructure.RiskLimitRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class RuleResult(
    val rule: String,
    val passed: Boolean,
    val detail: String,
    val current: Double,
    val limit: Double,
)

data class RiskCheckResult(
    val approved: Boolean,
    val blockedBy: String?,
    val severity: String,
    val checks: List<RuleResult>,
)

@Service
class RiskCheckerService(
    private val riskLimitRepo: RiskLimitRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun check(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
    ): RiskCheckResult {
        val limits = riskLimitRepo.findByUserId(userId).orElseGet {
            com.monticker.api.matching.domain.RiskLimit(userId = userId)
        }

        val checks = mutableListOf<RuleResult>()
        var blockedBy: String? = null

        // 1. Daily Loss Rule
        val dailyPnl = jdbc.queryForObject(
            """SELECT COALESCE(SUM(CASE WHEN side='SELL' THEN amount ELSE -amount END), 0)
               FROM fills WHERE user_id = ? AND filled_at >= current_date""",
            BigDecimal::class.java, userId
        ) ?: BigDecimal.ZERO

        val accountCash = jdbc.queryForObject(
            "SELECT COALESCE(cash, 0) FROM paper_accounts WHERE user_id = ?",
            BigDecimal::class.java, userId
        ) ?: BigDecimal("10000000")

        val lossLimitAmt = accountCash.multiply(limits.dailyLossLimitPct).divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val dailyLossPassed = dailyPnl >= lossLimitAmt.negate()
        val dailyLossRule = RuleResult(
            rule = "DailyLossRule",
            passed = dailyLossPassed,
            detail = "일간 손실 ${dailyPnl.toPlainString()} / 한도 ${lossLimitAmt.negate().toPlainString()}",
            current = dailyPnl.toDouble(),
            limit = lossLimitAmt.negate().toDouble(),
        )
        checks.add(dailyLossRule)
        if (!dailyLossPassed && blockedBy == null) blockedBy = "DailyLossRule"

        // 2. Concentration Rule (only for BUY)
        if (side == "BUY") {
            val holdingRows = jdbc.queryForList(
                """SELECT stock_id, SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) as qty,
                          AVG(CASE WHEN side='BUY' THEN price END) as avg_price
                   FROM paper_trades WHERE user_id = ? GROUP BY stock_id HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
                userId
            )
            val totalStockValue = holdingRows.sumOf { row ->
                val hStockId = (row["stock_id"] as Number).toLong()
                val hQty = (row["qty"] as Number).toInt()
                val curPrice = runCatching {
                    jdbc.queryForObject(
                        "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                        BigDecimal::class.java, hStockId
                    ) ?: BigDecimal.ZERO
                }.getOrDefault(BigDecimal.ZERO)
                curPrice.multiply(BigDecimal(hQty)).toDouble()
            }
            val totalAssets = accountCash.toDouble() + totalStockValue
            val currentHoldingRow = holdingRows.find { (it["stock_id"] as Number).toLong() == stockId }
            val currentHoldingQty = currentHoldingRow?.let { (it["qty"] as Number).toInt() } ?: 0
            val currentHoldingValue = estimatedPrice.multiply(BigDecimal(currentHoldingQty)).toDouble()
            val newHoldingValue = currentHoldingValue + estimatedPrice.multiply(BigDecimal(qty)).toDouble()
            val concentrationPct = if (totalAssets > 0) newHoldingValue / totalAssets * 100 else 0.0
            val concentrationLimit = limits.concentrationLimitPct.toDouble()
            val concentrationPassed = concentrationPct <= concentrationLimit
            checks.add(RuleResult(
                rule = "ConcentrationRule",
                passed = concentrationPassed,
                detail = "집중도 ${String.format("%.2f", concentrationPct)}% / 한도 ${concentrationLimit}%",
                current = concentrationPct,
                limit = concentrationLimit,
            ))
            if (!concentrationPassed && blockedBy == null) blockedBy = "ConcentrationRule"
        }

        // 3. VaR Rule
        val stockIds = jdbc.queryForList(
            """SELECT DISTINCT stock_id FROM paper_trades WHERE user_id = ?""",
            Long::class.java, userId
        )
        val varValue = if (stockIds.isNotEmpty()) {
            val placeholders = stockIds.joinToString(",") { "?" }
            val returns = jdbc.queryForList(
                """SELECT stock_id, close FROM candles_1d WHERE stock_id IN ($placeholders)
                   ORDER BY stock_id, candle_time DESC LIMIT ${stockIds.size * 20}""",
                *stockIds.toTypedArray()
            )
            val grouped = returns.groupBy { (it["stock_id"] as Number).toLong() }
            val allReturns = grouped.values.flatMap { rows ->
                rows.map { (it["close"] as BigDecimal).toDouble() }
                    .zipWithNext { a, b -> if (b != 0.0) (a - b) / b else 0.0 }
            }
            if (allReturns.size >= 5) {
                val sorted = allReturns.sorted()
                val idx = (sorted.size * 0.05).toInt().coerceAtLeast(0)
                -sorted[idx] * 100
            } else {
                val std = if (allReturns.isEmpty()) 0.0 else {
                    val mean = allReturns.average()
                    Math.sqrt(allReturns.sumOf { (it - mean) * (it - mean) } / allReturns.size)
                }
                std * 1.65 * 100
            }
        } else 0.0

        val varLimit = limits.varLimitPct.toDouble()
        val varPassed = varValue <= varLimit
        checks.add(RuleResult(
            rule = "VaRRule",
            passed = varPassed,
            detail = "VaR(95%) ${String.format("%.2f", varValue)}% / 한도 ${varLimit}%",
            current = varValue,
            limit = varLimit,
        ))
        if (!varPassed && blockedBy == null) blockedBy = "VaRRule"

        // 4. Position Count Rule (only for BUY of new stock)
        if (side == "BUY") {
            val positionCount = jdbc.queryForObject(
                """SELECT COUNT(DISTINCT stock_id) FROM paper_trades WHERE user_id = ?
                   AND stock_id NOT IN (
                       SELECT DISTINCT pt2.stock_id FROM paper_trades pt2
                       WHERE pt2.user_id = ?
                       GROUP BY pt2.stock_id
                       HAVING SUM(CASE WHEN pt2.side='BUY' THEN pt2.quantity ELSE -pt2.quantity END) <= 0
                   )""",
                Long::class.java, userId, userId
            ) ?: 0L
            val isNewStock = jdbc.queryForObject(
                """SELECT COALESCE(SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END), 0)
                   FROM paper_trades WHERE user_id = ? AND stock_id = ?""",
                Int::class.java, userId, stockId
            ) ?: 0
            if (isNewStock <= 0) {
                val maxPos = limits.maxPositionCount.toDouble()
                val posCountPassed = positionCount < maxPos.toLong()
                checks.add(RuleResult(
                    rule = "PositionCountRule",
                    passed = posCountPassed,
                    detail = "보유 종목 ${positionCount}개 / 한도 ${maxPos.toInt()}개",
                    current = positionCount.toDouble(),
                    limit = maxPos,
                ))
                if (!posCountPassed && blockedBy == null) blockedBy = "PositionCountRule"
            }
        }

        // 5. Trading Frequency Rule
        val oneHourAgo = Instant.now().minusSeconds(3600)
        val hourlyOrders = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE user_id = ? AND created_at > ?",
            Long::class.java, userId, java.sql.Timestamp.from(oneHourAgo)
        ) ?: 0L
        val freqLimit = limits.maxHourlyOrders.toDouble()
        val freqPassed = hourlyOrders < freqLimit.toLong()
        checks.add(RuleResult(
            rule = "TradingFrequencyRule",
            passed = freqPassed,
            detail = "시간당 주문 ${hourlyOrders}건 / 한도 ${freqLimit.toInt()}건",
            current = hourlyOrders.toDouble(),
            limit = freqLimit,
        ))
        if (!freqPassed && blockedBy == null) blockedBy = "TradingFrequencyRule"

        val approved = blockedBy == null
        val severity = when {
            !approved -> "BLOCKED"
            checks.any { !it.passed } -> "WARNING"
            else -> "APPROVED"
        }

        // Save log
        val checksJson = objectMapper.writeValueAsString(checks)
        jdbc.update(
            """INSERT INTO risk_check_logs (user_id, stock_id, side, quantity, approved, blocked_by, checks_json)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
            userId, stockId, side, qty, approved, blockedBy, checksJson
        )

        return RiskCheckResult(approved = approved, blockedBy = blockedBy, severity = severity, checks = checks)
    }
}
