package com.monticker.api.matching.api

import com.monticker.api.matching.application.RiskCheckResult
import com.monticker.api.matching.application.RiskCheckerService
import com.monticker.api.matching.domain.RiskLimit
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.infrastructure.RiskLimitRepository
import com.monticker.api.matching.domain.OrderStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class RiskLimitsDto(
    val dailyLossLimitPct: BigDecimal,
    val concentrationLimitPct: BigDecimal,
    val varLimitPct: BigDecimal,
    val maxPositionCount: Int,
    val maxHourlyOrders: Int,
    val isActive: Boolean,
)

data class UpdateRiskLimitsRequest(
    val dailyLossLimitPct: BigDecimal?,
    val concentrationLimitPct: BigDecimal?,
    val varLimitPct: BigDecimal?,
    val maxPositionCount: Int?,
    val maxHourlyOrders: Int?,
)

data class DryRunCheckRequest(
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val estimatedPrice: BigDecimal,
)

data class ConcentrationItem(
    val stockId: Long,
    val symbol: String,
    val valuePct: Double,
)

data class RiskExposureResponse(
    val totalAssets: BigDecimal,
    val availableCash: BigDecimal,
    val dailyPnl: BigDecimal,
    val dailyPnlPct: Double,
    val topConcentration: ConcentrationItem?,
    val estimatedVaR: Double,
    val activeOrderCount: Int,
    val hourlyOrderCount: Int,
    val limits: RiskLimitsDto,
)

@Validated
@RestController
@RequestMapping("/api/risk")
class RiskController(
    private val riskLimitRepo: RiskLimitRepository,
    private val riskChecker: RiskCheckerService,
    private val orderRepo: OrderRepository,
    private val jdbc: JdbcTemplate,
) {
    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/limits")
    fun getRiskLimits(): ResponseEntity<RiskLimitsDto> {
        val limits = riskLimitRepo.findByUserId(userId()).orElseGet {
            riskLimitRepo.save(RiskLimit(userId = userId()))
        }
        return ResponseEntity.ok(limits.toDto())
    }

    @PutMapping("/limits")
    fun updateRiskLimits(@RequestBody req: UpdateRiskLimitsRequest): ResponseEntity<RiskLimitsDto> {
        val limits = riskLimitRepo.findByUserId(userId()).orElseGet {
            riskLimitRepo.save(RiskLimit(userId = userId()))
        }
        req.dailyLossLimitPct?.let { limits.dailyLossLimitPct = it }
        req.concentrationLimitPct?.let { limits.concentrationLimitPct = it }
        req.varLimitPct?.let { limits.varLimitPct = it }
        req.maxPositionCount?.let { limits.maxPositionCount = it }
        req.maxHourlyOrders?.let { limits.maxHourlyOrders = it }
        limits.updatedAt = Instant.now()
        return ResponseEntity.ok(riskLimitRepo.save(limits).toDto())
    }

    @PostMapping("/check")
    fun dryRunCheck(@RequestBody req: DryRunCheckRequest): ResponseEntity<RiskCheckResult> {
        val result = riskChecker.check(userId(), req.stockId, req.side, req.quantity, req.estimatedPrice)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/exposure")
    fun getCurrentExposure(): ResponseEntity<RiskExposureResponse> {
        val limits = riskLimitRepo.findByUserId(userId()).orElseGet {
            riskLimitRepo.save(RiskLimit(userId = userId()))
        }

        val cash = jdbc.queryForObject(
            "SELECT COALESCE(cash, 0) FROM paper_accounts WHERE user_id = ?",
            BigDecimal::class.java, userId()
        ) ?: BigDecimal("10000000")

        // Holdings
        data class Holding(val stockId: Long, val qty: Int, val currentPrice: BigDecimal)
        val holdingRows = jdbc.queryForList(
            """SELECT stock_id, SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) as qty
               FROM paper_trades WHERE user_id = ? GROUP BY stock_id
               HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
            userId()
        )
        val holdings = holdingRows.mapNotNull { row ->
            val stockId = (row["stock_id"] as Number).toLong()
            val qty = (row["qty"] as Number).toInt()
            val price = runCatching {
                jdbc.queryForObject(
                    "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                    BigDecimal::class.java, stockId
                ) ?: BigDecimal.ZERO
            }.getOrDefault(BigDecimal.ZERO)
            Holding(stockId, qty, price)
        }

        val stockValue = holdings.fold(BigDecimal.ZERO) { acc, h ->
            acc + h.currentPrice.multiply(BigDecimal(h.qty))
        }
        val totalAssets = cash + stockValue

        // Daily P&L
        val dailyPnl = jdbc.queryForObject(
            """SELECT COALESCE(SUM(CASE WHEN side='SELL' THEN amount ELSE -amount END), 0)
               FROM fills WHERE user_id = ? AND filled_at >= current_date""",
            BigDecimal::class.java, userId()
        ) ?: BigDecimal.ZERO
        val dailyPnlPct = if (totalAssets > BigDecimal.ZERO)
            dailyPnl.divide(totalAssets, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble()
        else 0.0

        // Top concentration
        val topConcentration = if (holdings.isNotEmpty() && totalAssets > BigDecimal.ZERO) {
            holdings.maxByOrNull { it.currentPrice.multiply(BigDecimal(it.qty)) }?.let { h ->
                val pct = h.currentPrice.multiply(BigDecimal(h.qty))
                    .divide(totalAssets, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100")).toDouble()
                val symbol = runCatching {
                    jdbc.queryForObject("SELECT symbol FROM stocks WHERE id = ?", String::class.java, h.stockId)
                }.getOrDefault("?") ?: "?"
                ConcentrationItem(h.stockId, symbol, pct)
            }
        } else null

        // Estimated VaR
        val stockIds = holdings.map { it.stockId }
        val estimatedVaR = if (stockIds.isNotEmpty()) {
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
                val mean = if (allReturns.isEmpty()) 0.0 else allReturns.average()
                val std = if (allReturns.isEmpty()) 0.0 else
                    Math.sqrt(allReturns.sumOf { (it - mean) * (it - mean) } / allReturns.size)
                std * 1.65 * 100
            }
        } else 0.0

        // Active and hourly orders
        val activeOrderCount = orderRepo.findByUserIdAndStatusIn(
            userId(), listOf(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)
        ).size
        val oneHourAgo = Instant.now().minusSeconds(3600)
        val hourlyOrderCount = orderRepo.countByUserIdAndCreatedAtAfter(userId(), oneHourAgo).toInt()

        return ResponseEntity.ok(RiskExposureResponse(
            totalAssets = totalAssets,
            availableCash = cash,
            dailyPnl = dailyPnl,
            dailyPnlPct = dailyPnlPct,
            topConcentration = topConcentration,
            estimatedVaR = estimatedVaR,
            activeOrderCount = activeOrderCount,
            hourlyOrderCount = hourlyOrderCount,
            limits = limits.toDto(),
        ))
    }

    private fun RiskLimit.toDto() = RiskLimitsDto(
        dailyLossLimitPct = dailyLossLimitPct,
        concentrationLimitPct = concentrationLimitPct,
        varLimitPct = varLimitPct,
        maxPositionCount = maxPositionCount,
        maxHourlyOrders = maxHourlyOrders,
        isActive = isActive,
    )
}
