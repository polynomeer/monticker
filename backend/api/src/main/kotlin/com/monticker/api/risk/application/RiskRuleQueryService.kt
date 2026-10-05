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
    /**
     * ADR-058 — 아직 보유 내역에 없을 수 있는 매수(stockId → 수량): 결과 불명·미체결 잔량·막 체결된 것. 노출을 늘리는 쪽으로만
     * 센다(진행 중 매도는 넣지 않는다). 모의투자는 사가가 현금·보유를 원자적으로 바꾸므로 비어 있다.
     */
    val pendingBuys: Map<Long, Int> = emptyMap(),
    /**
     * ADR-058 — 집중도 분모. 실거래는 증권사가 계산한 총평가액(KIS `tot_evlu_amt`는 D+2 예수금 기준이라 오늘 매수 대금이
     * 이중으로 들어가지 않는다). null이면 `cash + Σ보유`(모의투자 — 체결 즉시 현금이 줄어든다).
     */
    val totalAssets: BigDecimal? = null,
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

        // 0. Quantity Guard — 음수/0 수량은 집중도·VaR 계산을 newHoldingValue≈0으로 무력화시켜
        // 실노출과 무관하게 통과시킨다(V-H3). 어떤 side든 상류 검증을 못 믿는다는 가정 하에 여기서도 거부한다.
        if (qty <= 0) {
            checks.add(RuleResult(
                rule    = "QuantityRule",
                passed  = false,
                detail  = "주문 수량은 0보다 커야 합니다: $qty",
                current = qty.toDouble(),
                limit   = 0.0,
            ))
        }

        // 1. Daily Loss Rule (BUY only — ADR-063) — 한도 기준 금액은 실거래면 증권사 총평가액(KIS 예수금은 정산 전 매수 대금이
        // 남아 부푼다), 모의투자면 현금(체결 즉시 줄어든다).
        // 매도에는 걸지 않는다(VaR와 같은 이유, ADR-063): 손실 한도는 위험을 더 늘리는 주문을 멈추는 장치다. 매도까지 막으면 손실 매도
        // 한 번 뒤에 스탑로스·리밸런싱 매도가 전부 막혀 사용자를 떨어지는 포지션에 가둔다(일간 손실이 실현손익이 되면서 드러났다).
        val lossLimitAmt = (snapshot.totalAssets ?: accountCash).multiply(limits.dailyLossLimitPct)
            .divide(BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP)
        val dailyLossPassed = snapshot.dailyPnl >= lossLimitAmt.negate()
        if (side == "BUY") checks.add(RuleResult(
            rule    = "DailyLossRule",
            passed  = dailyLossPassed,
            detail  = "일간 손실 ${snapshot.dailyPnl.toPlainString()} / 한도 ${lossLimitAmt.negate().toPlainString()}",
            current = snapshot.dailyPnl.toDouble(),
            limit   = lossLimitAmt.negate().toDouble(),
        ))

        // 2. Concentration Rule (BUY only)
        if (side == "BUY") {
            val concentrationLimit = limits.concentrationLimitPct.toDouble()
            if (estimatedPrice <= BigDecimal.ZERO) {
                // 추정가를 못 구한 경우(V-H3) — newHoldingValue≈0이 되어 통과해버리는 대신
                // 보수적으로 거부한다. 값을 모르는데 안전하다고 판정할 수는 없다.
                checks.add(RuleResult(
                    rule    = "ConcentrationRule",
                    passed  = false,
                    detail  = "추정가를 확인할 수 없어 집중도를 판정할 수 없습니다.",
                    current = 0.0,
                    limit   = concentrationLimit,
                ))
            } else {
                val totalAssets = snapshot.totalAssets?.toDouble() ?: (accountCash.toDouble() + snapshot.holdings.sumOf { h ->
                    currentPrice(h.stockId).multiply(BigDecimal(h.qty)).toDouble()
                })
                val currentQty       = snapshot.holdings.find { it.stockId == stockId }?.qty ?: 0
                // ADR-058 — 같은 종목의 진행 중 매수까지 더한다. 빠지면 한도 30%에 25%짜리 매수 두 건이 각각 통과한다.
                val pendingQty       = snapshot.pendingBuys[stockId] ?: 0
                val newHoldingValue  = estimatedPrice.multiply(BigDecimal(currentQty + pendingQty + qty)).toDouble()
                if (snapshot.totalAssets != null && totalAssets <= 0) {
                    // 증권사가 총평가액을 주지 않았다 — 분모를 모르는데 안전하다고 판정하지 않는다(V-H3와 같은 원칙).
                    checks.add(RuleResult(
                        rule    = "ConcentrationRule",
                        passed  = false,
                        detail  = "증권사 총평가액을 확인할 수 없어 집중도를 판정할 수 없습니다.",
                        current = 0.0,
                        limit   = concentrationLimit,
                    ))
                } else {
                    val concentrationPct = if (totalAssets > 0) newHoldingValue / totalAssets * 100 else 0.0
                    val pendingNote = if (pendingQty > 0) " (보유 $currentQty + 대기 $pendingQty + 주문 $qty)" else ""
                    checks.add(RuleResult(
                        rule    = "ConcentrationRule",
                        passed  = concentrationPct <= concentrationLimit,
                        detail  = "집중도 ${String.format("%.2f", concentrationPct)}% / 한도 ${concentrationLimit}%$pendingNote",
                        current = concentrationPct,
                        limit   = concentrationLimit,
                    ))
                }
            }
        }

        // 2-1. Sector Concentration Rule (BUY only, 한도 설정 시 — ADR-069)
        val sectorLimit = limits.sectorConcentrationLimitPct
        if (side == "BUY" && sectorLimit != null) {
            checks.add(sectorConcentration(stockId, qty, estimatedPrice, sectorLimit.toDouble(), snapshot))
        }

        // 3. VaR Rule (BUY only — ADR-047)
        // 노출 한도는 위험을 늘리는 주문만 막아야 한다. 매도는 노출을 줄이는데, 이전엔 보유 종목의 VaR가 한도를 넘으면
        // 매도까지 막혀 "위험한 포지션을 정리할 수 없는" 상태가 됐다(로컬 데이터의 −72% 일봉으로 재현). 파사드 전환으로
        // 포트폴리오 화면의 매도에도 이 게이트가 걸리게 되면서 드러났다.
        val varValue = if (side == "BUY") historicalVaRPct(snapshot.holdings.map { it.stockId }) else 0.0
        val varLimit = limits.varLimitPct.toDouble()
        if (side == "BUY") checks.add(RuleResult(
            rule    = "VaRRule",
            passed  = varValue <= varLimit,
            detail  = "VaR(95%) ${String.format("%.2f", varValue)}% / 한도 ${varLimit}%",
            current = varValue,
            limit   = varLimit,
        ))

        // 4. Position Count Rule (BUY + new stock only)
        if (side == "BUY") {
            // ADR-058 — 진행 중 매수 종목도 보유로 센다. 빠지면 신규 종목 매수를 여러 건 걸어 한도를 넘는다.
            val positions = snapshot.holdings.map { it.stockId }.toSet() + snapshot.pendingBuys.filterValues { it > 0 }.keys
            val isNewStock = stockId !in positions
            if (isNewStock) {
                val positionCount = positions.size
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

    /**
     * 보유 종목들의 1일 역사적 VaR(95%, %) — 최근 20개 확정 일봉 수익률의 5% 분위수. 매수 게이트(VaRRule)와 한도 근접 경고
     * (RiskLimitNearWarningJob)가 같은 값을 보도록 한 곳에 둔다. 수익률이 5개 미만이면 정규 근사(1.65σ)로 대신한다.
     * candles_1d의 "오늘" 행은 장중 계속 바뀌는 미확정 값이라 제외한다.
     */
    fun historicalVaRPct(holdingStockIds: List<Long>): Double {
        val stockIds = holdingStockIds.distinct()
        if (stockIds.isEmpty()) return 0.0
        val placeholders = stockIds.joinToString(",") { "?" }
        val todayStartKst = Instant.now().atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()
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
        return if (allReturns.size >= 5) {
            val sorted = allReturns.sorted()
            -sorted[(sorted.size * 0.05).toInt().coerceAtLeast(0)] * 100
        } else {
            val mean = if (allReturns.isEmpty()) 0.0 else allReturns.average()
            val std  = if (allReturns.isEmpty()) 0.0 else
                Math.sqrt(allReturns.sumOf { (it - mean) * (it - mean) } / allReturns.size)
            std * 1.65 * 100
        }
    }

    /**
     * 종목의 최근가(candles_1m 마지막 close). 보유 종목 평가와 MARKET 주문의 추정가 폴백
     * (RiskCheckerService.check) 양쪽이 쓴다 — 사가(OrderSagaOrchestrator.getCurrentPrice)가
     * 예약금을 잡는 기준과 같은 조회라, 게이트와 예약이 서로 다른 가격을 보지 않는다.
     * 캔들이 없으면 ZERO — 호출자가 "가격 불명"으로 보수적으로 처리해야 한다(V-H3).
     *
     * queryForObject는 결과가 0건이면 EmptyResultDataAccessException을 던진다 — 아직 한 번도
     * 거래하지 않아 paper_accounts/캔들 행이 없는 사용자가 있으면 GlobalExceptionHandler의
     * catch-all에 잡혀 안내 메시지 없는 500으로 샌다. query+firstOrNull은 0건이어도 예외 없이
     * 빈 리스트를 준다. (PaperTradingService.getCurrentPrice와 동일한 패턴)
     */
    internal fun currentPrice(stockId: Long): BigDecimal =
        jdbc.query(
            "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
            { rs, _ -> rs.getBigDecimal("close") },
            stockId,
        ).firstOrNull() ?: BigDecimal.ZERO

    /**
     * ADR-069 — 주문 종목이 속한 섹터의 합산 비중(보유 + 진행 중 매수 + 이번 주문) / 총자산 ≤ 한도.
     * 단일 종목 집중도와 같은 분모·같은 "모르면 막는다" 원칙(V-H3)을 쓴다. 단 섹터가 미분류인 종목은 어떤 섹터 합에도 들어가지
     * 않으므로 이 규칙의 대상이 아니다(통과, 사유에 명시).
     */
    private fun sectorConcentration(
        stockId: Long,
        qty: Int,
        estimatedPrice: BigDecimal,
        limit: Double,
        snapshot: PortfolioSnapshot,
    ): RuleResult {
        fun result(passed: Boolean, detail: String, current: Double = 0.0) =
            RuleResult(rule = "SectorConcentrationRule", passed = passed, detail = detail, current = current, limit = limit)

        val sector = sectorsOf(listOf(stockId))[stockId]
            ?: return result(true, "섹터 미분류 종목 — 섹터 한도 대상이 아닙니다.")
        if (estimatedPrice <= BigDecimal.ZERO) return result(false, "추정가를 확인할 수 없어 섹터 집중도를 판정할 수 없습니다.")

        val held = snapshot.holdings.groupBy { it.stockId }.mapValues { (_, v) -> v.sumOf { it.qty } }
        val ids = (held.keys + snapshot.pendingBuys.keys + stockId).distinct()
        val sectors = sectorsOf(ids)
        val prices = mutableMapOf<Long, BigDecimal>()
        fun priceOf(id: Long) = prices.getOrPut(id) { if (id == stockId) estimatedPrice else currentPrice(id) }

        // 분모 — 단일 종목 집중도와 같다: 실거래는 증권사 총평가액, 모의투자는 현금 + 보유 평가액.
        val totalAssets = snapshot.totalAssets?.toDouble()
            ?: (snapshot.cash.toDouble() + held.entries.sumOf { (id, q) -> currentPrice(id).multiply(BigDecimal(q)).toDouble() })
        if (totalAssets <= 0) return result(false, "총자산을 확인할 수 없어 섹터 집중도를 판정할 수 없습니다.")

        var sectorValue = 0.0
        for (id in ids.filter { sectors[it] == sector }) {
            val q = (held[id] ?: 0) + (snapshot.pendingBuys[id] ?: 0) + (if (id == stockId) qty else 0)
            if (q <= 0) continue
            val p = priceOf(id)
            // 같은 섹터 보유의 가격을 모르면 합을 과소평가해 통과시키게 된다 — 막는다.
            if (p <= BigDecimal.ZERO) return result(false, "같은 섹터 보유 종목의 가격을 확인할 수 없어 섹터 집중도를 판정할 수 없습니다.")
            sectorValue += p.multiply(BigDecimal(q)).toDouble()
        }
        val pct = sectorValue / totalAssets * 100
        return result(pct <= limit, "섹터($sector) 집중도 ${String.format("%.2f", pct)}% / 한도 ${limit}%", pct)
    }

    /** 종목 → 섹터. 섹터가 비어 있는 종목은 결과에 없다(미분류). */
    internal fun sectorsOf(stockIds: Collection<Long>): Map<Long, String> {
        if (stockIds.isEmpty()) return emptyMap()
        val ids = stockIds.distinct()
        return jdbc.query(
            "SELECT id, sector FROM stocks WHERE id IN (${ids.joinToString(",") { "?" }}) AND sector IS NOT NULL AND sector <> ''",
            { rs, _ -> rs.getLong("id") to rs.getString("sector") },
            *ids.toTypedArray(),
        ).toMap()
    }

    internal fun symbolOf(stockId: Long): String? =
        jdbc.query("SELECT symbol FROM stocks WHERE id = ?", { rs, _ -> rs.getString(1) }, stockId).firstOrNull()

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 계좌의 실행 기록은 paper_trades 하나다 (ADR-047: 매칭 엔진 체결도 PaperExecutionListener가 여기 미러링한다).
         * ADR-047 이전엔 fills와의 합집합을 봤다 — 이제 합집합이면 같은 체결을 두 번 센다.
         */
        private const val PAPER_TRADES_CTE = """
            WITH t AS (
                SELECT stock_id, side, quantity, amount, traded_at AS at FROM paper_trades WHERE user_id = ?
            )"""

        /**
         * 오늘의 **실현 손익** — 오늘 매도한 수량 × (매도가 − 평균 매수단가).
         * 평단가는 이동평균법(누적 매수금액 ÷ 누적 매수수량, 매도해도 변하지 않는다 — 증권사 평단가 방식).
         * 이전 구현은 `SUM(SELL amount − BUY amount)`, 즉 현금 흐름이었다. 매수 자체가 "손실"로 잡혀
         * 1,000만 계좌에서 하루 30만 원(3%)만 사면 모든 매수가 차단됐다(CH-05에서 발견).
         */
        const val REALIZED_PNL_TODAY_SQL = PAPER_TRADES_CTE + """,
            cost AS (
                SELECT stock_id, SUM(amount) / NULLIF(SUM(quantity), 0) AS avg_cost
                FROM t WHERE side = 'BUY' GROUP BY stock_id
            )
            SELECT COALESCE(SUM(t.amount - t.quantity * c.avg_cost), 0)
            FROM t JOIN cost c USING (stock_id)
            WHERE t.side = 'SELL' AND t.at >= ?"""

        /** 보유 종목 — 순수량. */
        const val HOLDINGS_SQL = PAPER_TRADES_CTE + """
            SELECT stock_id, SUM(CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END) AS qty
            FROM t GROUP BY stock_id
            HAVING SUM(CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END) > 0"""
    }

    /**
     * 모의계좌의 오늘(KST) 실현 손익. 일간 손실 게이트와 화면 표시(/api/risk/exposure)가 같은 값을 쓰도록 한 곳에 둔다.
     * "오늘"은 KST 기준 — current_date는 DB 세션 타임존(UTC)이라 새벽 거래가 전날로 붙었다.
     */
    fun paperRealizedPnlToday(userId: Long): BigDecimal {
        val todayStartKst = Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        return jdbc.query(
            REALIZED_PNL_TODAY_SQL.trimIndent(),
            { rs, _ -> rs.getBigDecimal(1) },
            userId, java.sql.Timestamp.from(todayStartKst),
        ).firstOrNull() ?: BigDecimal.ZERO
    }

    /** 모의계좌의 현재 포트폴리오 상태 — 매수 게이트와 한도 근접 경고가 같이 쓴다. */
    fun paperSnapshot(userId: Long): PortfolioSnapshot {
        val accountCash = jdbc.query(
            "SELECT COALESCE(cash, 0) FROM paper_accounts WHERE user_id = ?",
            { rs, _ -> rs.getBigDecimal(1) },
            userId,
        ).firstOrNull() ?: BigDecimal("10000000")

        val dailyPnl = paperRealizedPnlToday(userId)

        val holdings = jdbc.queryForList(HOLDINGS_SQL.trimIndent(), userId).map { row ->
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
