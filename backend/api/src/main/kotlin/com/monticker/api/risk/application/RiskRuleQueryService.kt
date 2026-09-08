package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** ADR-025 — 계좌 유형(페이퍼/실거래)에 무관한, 리스크 룰 판정에 필요한 포트폴리오 상태. */
@NamedInterface("api")
data class HoldingPosition(val stockId: Long, val qty: Int)

@NamedInterface("api")
data class PortfolioSnapshot(
    val cash: BigDecimal,
    val holdings: List<HoldingPosition>,
    val dailyPnl: BigDecimal,
    val recentOrderCount: Long,   // 최근 1시간
)

@Service
@Transactional(readOnly = true)
class RiskRuleQueryService(
    private val jdbc: JdbcTemplate,
) {
    /** 페이퍼 트레이딩 — 기존 호출부(RiskCheckedAspect) 그대로, 동작 변화 없음. */
    fun evaluate(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
        limits: RiskLimit,
    ): List<RuleResult> =
        evaluateWithSnapshot(stockId, side, qty, estimatedPrice, limits, paperSnapshot(userId))

    /** 실거래 — 호출자(BrokerageService)가 브로커 API/로컬 테이블에서 직접 조립한 스냅샷을 넘긴다. */
    fun evaluateWithSnapshot(
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
        limits: RiskLimit,
        snapshot: PortfolioSnapshot,
    ): List<RuleResult> {
        val checks = mutableListOf<RuleResult>()
        val accountCash = snapshot.cash

        // 1. Daily Loss Rule
        val lossLimitAmt = accountCash.multiply(limits.dailyLossLimitPct)
            .divide(BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP)
        val dailyLossPassed = snapshot.dailyPnl >= lossLimitAmt.negate()
        checks.add(RuleResult(
            rule    = "DailyLossRule",
            passed  = dailyLossPassed,
            detail  = "일간 손실 ${snapshot.dailyPnl.toPlainString()} / 한도 ${lossLimitAmt.negate().toPlainString()}",
            current = snapshot.dailyPnl.toDouble(),
            limit   = lossLimitAmt.negate().toDouble(),
        ))

        // 2. Concentration Rule (BUY only)
        if (side == "BUY") {
            val totalStockValue = snapshot.holdings.sumOf { h ->
                currentPrice(h.stockId).multiply(BigDecimal(h.qty)).toDouble()
            }
            val totalAssets      = accountCash.toDouble() + totalStockValue
            val currentQty       = snapshot.holdings.find { it.stockId == stockId }?.qty ?: 0
            val currentValue     = estimatedPrice.multiply(BigDecimal(currentQty)).toDouble()
            val newHoldingValue  = currentValue + estimatedPrice.multiply(BigDecimal(qty)).toDouble()
            val concentrationPct = if (totalAssets > 0) newHoldingValue / totalAssets * 100 else 0.0
            val concentrationLimit = limits.concentrationLimitPct.toDouble()
            checks.add(RuleResult(
                rule    = "ConcentrationRule",
                passed  = concentrationPct <= concentrationLimit,
                detail  = "집중도 ${String.format("%.2f", concentrationPct)}% / 한도 ${concentrationLimit}%",
                current = concentrationPct,
                limit   = concentrationLimit,
            ))
        }

        // 3. VaR Rule
        val stockIds = snapshot.holdings.map { it.stockId }.distinct()
        val varValue = if (stockIds.isNotEmpty()) {
            val placeholders = stockIds.joinToString(",") { "?" }
            // candles_1d의 "오늘" 행은 장중 계속 바뀌는 미확정 값이라 VaR 수익률 계산에서 제외한다.
            val todayStartKst = Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            val rows = jdbc.queryForList(
                """SELECT stock_id, close FROM candles_1d
                   WHERE stock_id IN ($placeholders) AND candle_time < ?
                   ORDER BY stock_id, candle_time DESC LIMIT ${stockIds.size * 20}""",
                *stockIds.toTypedArray(), java.sql.Timestamp.from(todayStartKst),
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
            val isNewStock = snapshot.holdings.none { it.stockId == stockId }
            if (isNewStock) {
                val positionCount = snapshot.holdings.size
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
        val freqLimit = limits.maxHourlyOrders.toDouble()
        checks.add(RuleResult(
            rule    = "TradingFrequencyRule",
            passed  = snapshot.recentOrderCount < freqLimit.toLong(),
            detail  = "시간당 주문 ${snapshot.recentOrderCount}건 / 한도 ${freqLimit.toInt()}건",
            current = snapshot.recentOrderCount.toDouble(),
            limit   = freqLimit,
        ))

        return checks
    }

    // queryForObject는 결과가 0건이면 EmptyResultDataAccessException을 던진다 — 아직 한 번도
    // 거래하지 않아 paper_accounts/캔들 행이 없는 사용자가 있으면 GlobalExceptionHandler의
    // catch-all에 잡혀 안내 메시지 없는 500으로 샌다. query+firstOrNull은 0건이어도 예외 없이
    // 빈 리스트를 준다. (PaperTradingService.getCurrentPrice와 동일한 패턴)
    private fun currentPrice(stockId: Long): BigDecimal =
        jdbc.query(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            { rs, _ -> rs.getBigDecimal("close") },
            stockId,
        ).firstOrNull() ?: BigDecimal.ZERO

    private fun paperSnapshot(userId: Long): PortfolioSnapshot {
        val accountCash = jdbc.query(
            "SELECT COALESCE(cash, 0) FROM paper_accounts WHERE user_id = ?",
            { rs, _ -> rs.getBigDecimal(1) },
            userId,
        ).firstOrNull() ?: BigDecimal("10000000")

        val dailyPnl = jdbc.query(
            """SELECT COALESCE(SUM(CASE WHEN side='SELL' THEN amount ELSE -amount END), 0)
               FROM fills WHERE user_id = ? AND filled_at >= current_date""",
            { rs, _ -> rs.getBigDecimal(1) },
            userId,
        ).firstOrNull() ?: BigDecimal.ZERO

        val holdings = jdbc.queryForList(
            """SELECT stock_id, SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) as qty
               FROM paper_trades WHERE user_id = ?
               GROUP BY stock_id
               HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
            userId,
        ).map { row ->
            HoldingPosition(
                stockId = (row["stock_id"] as Number).toLong(),
                qty     = (row["qty"] as Number).toInt(),
            )
        }

        val oneHourAgo = Instant.now().minusSeconds(3600)
        val recentOrderCount = jdbc.query(
            "SELECT COUNT(*) FROM orders WHERE user_id = ? AND created_at > ?",
            { rs, _ -> rs.getLong(1) },
            userId, java.sql.Timestamp.from(oneHourAgo),
        ).firstOrNull() ?: 0L

        return PortfolioSnapshot(accountCash, holdings, dailyPnl, recentOrderCount)
    }
}
