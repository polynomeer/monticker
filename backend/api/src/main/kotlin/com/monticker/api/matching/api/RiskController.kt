package com.monticker.api.matching.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.risk.application.PendingLimitChange
import com.monticker.api.risk.application.RiskLimitService
import com.monticker.api.risk.application.RiskLimitsView
import com.monticker.api.risk.domain.RiskLimitField
import com.monticker.api.matching.infrastructure.OrderRepository
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
    /** ADR-069 — null = 섹터 한도 미설정 */
    val sectorConcentrationLimitPct: BigDecimal?,
    /** 모의투자 리스크 체크 활성화. 실거래 게이트는 이 값과 무관하게 항상 돈다(ADR-069). */
    val isActive: Boolean,
    /** ADR-069 — 24시간 뒤 적용될 완화 요청. 위 값은 지금 유효한 한도다. */
    val pendingChanges: List<PendingLimitChange> = emptyList(),
    val coolingOffHours: Long,
)

data class UpdateRiskLimitsRequest(
    val dailyLossLimitPct: BigDecimal?,
    val concentrationLimitPct: BigDecimal?,
    val varLimitPct: BigDecimal?,
    val maxPositionCount: Int?,
    val maxHourlyOrders: Int?,
    val sectorConcentrationLimitPct: BigDecimal? = null,
    /** true면 섹터 한도를 해제한다(미설정). JSON null은 "변경 없음"이라 해제를 따로 표현한다. */
    val clearSectorConcentrationLimit: Boolean = false,
    val isActive: Boolean? = null,
) {
    /** 보낸 항목만 바꾼다. 완화는 24시간 뒤, 강화는 즉시(RiskLimitService). */
    fun changes(): Map<RiskLimitField, BigDecimal?> = buildMap {
        dailyLossLimitPct?.let { put(RiskLimitField.DAILY_LOSS_LIMIT_PCT, it) }
        concentrationLimitPct?.let { put(RiskLimitField.CONCENTRATION_LIMIT_PCT, it) }
        varLimitPct?.let { put(RiskLimitField.VAR_LIMIT_PCT, it) }
        maxPositionCount?.let { put(RiskLimitField.MAX_POSITION_COUNT, BigDecimal(it)) }
        maxHourlyOrders?.let { put(RiskLimitField.MAX_HOURLY_ORDERS, BigDecimal(it)) }
        require(!(clearSectorConcentrationLimit && sectorConcentrationLimitPct != null)) {
            "섹터 한도 설정과 해제를 함께 보낼 수 없습니다."
        }
        sectorConcentrationLimitPct?.let { put(RiskLimitField.SECTOR_CONCENTRATION_LIMIT_PCT, it) }
        if (clearSectorConcentrationLimit) put(RiskLimitField.SECTOR_CONCENTRATION_LIMIT_PCT, null)
        isActive?.let { put(RiskLimitField.IS_ACTIVE, if (it) BigDecimal.ONE else BigDecimal.ZERO) }
    }
}

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
    private val limitService: RiskLimitService,
    private val riskChecker: RiskCheckerService,
    private val orderRepo: OrderRepository,
    private val jdbc: JdbcTemplate,
) {
    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/limits")
    fun getRiskLimits(): ResponseEntity<RiskLimitsDto> = ResponseEntity.ok(limitService.view(userId()).toDto())

    @PutMapping("/limits")
    fun updateRiskLimits(@RequestBody req: UpdateRiskLimitsRequest): ResponseEntity<RiskLimitsDto> =
        ResponseEntity.ok(limitService.update(userId(), req.changes()).toDto())

    /**
     * 사전 점검 — 주문 없이 게이트만 돌린다. 판정·감사 비용이 주문과 같으므로 호출 수를 제한하고, side·수량은 감사 행·메트릭
     * 라벨이 되기 전에 여기서 거부한다(보안 리뷰 2026-10). 감사 기록은 dry_run으로 남아 차단 기록·집계에 섞이지 않는다.
     */
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "risk.dryrun")
    @PostMapping("/check")
    fun dryRunCheck(@RequestBody req: DryRunCheckRequest): ResponseEntity<RiskCheckResult> {
        require(req.side == "BUY" || req.side == "SELL") { "side는 BUY 또는 SELL이어야 합니다." }
        require(req.quantity > 0) { "수량은 0보다 커야 합니다." }
        val result = riskChecker.dryRun(userId(), req.stockId, req.side, req.quantity, req.estimatedPrice)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/exposure")
    fun getCurrentExposure(): ResponseEntity<RiskExposureResponse> {
        val limits = limitService.view(userId())

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

        // Daily P&L — 일간 손실 게이트와 같은 값(오늘 KST 실현 손익). 이전엔 오늘 체결의 현금 흐름이라 매수가 손실로 보였다.
        val dailyPnl = riskChecker.paperRealizedPnlToday(userId())
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

    private fun RiskLimitsView.toDto() = RiskLimitsDto(
        dailyLossLimitPct = limits.dailyLossLimitPct,
        concentrationLimitPct = limits.concentrationLimitPct,
        varLimitPct = limits.varLimitPct,
        maxPositionCount = limits.maxPositionCount,
        maxHourlyOrders = limits.maxHourlyOrders,
        sectorConcentrationLimitPct = limits.sectorConcentrationLimitPct,
        isActive = limits.isActive,
        pendingChanges = pending,
        coolingOffHours = coolingOffHours,
    )
}
