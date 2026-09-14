package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperAccountQueryService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/** 미체결 BUY 주문 예약금 — WalletService와 LedgerReconciliationService가 같은 정의를 써야 한다. */
const val RESERVED_CASH_SQL = """
    SELECT COALESCE(SUM(limit_price * (quantity - filled_qty)), 0)
    FROM orders
    WHERE user_id = ? AND side = 'BUY' AND status IN ('PENDING', 'PARTIALLY_FILLED')
"""

data class WalletMapResponse(
    val availableCash: BigDecimal,
    val reservedCash: BigDecimal,
    val holdingsValue: BigDecimal,
    val settlementPending: BigDecimal,
    val totalAssets: BigDecimal,
    val recentLedger: List<LedgerEventDto>,
)

@Service
@Transactional(readOnly = true)
class WalletService(
    private val accountQueryService: PaperAccountQueryService,
    private val ledgerService: LedgerService,
    private val jdbc: JdbcTemplate,
) {

    fun getWalletMap(userId: Long): WalletMapResponse {
        val cash = accountQueryService.getCashBalance(userId)

        val holdingsValue = calcHoldingsValue(userId)
        val reservedCash = calcReservedCash(userId)
        val totalAssets = cash.amount + reservedCash + holdingsValue
        val recentLedger = ledgerService.getRecentLedger(userId, 10)

        return WalletMapResponse(
            availableCash = cash.amount,
            reservedCash = reservedCash,
            holdingsValue = holdingsValue,
            settlementPending = BigDecimal.ZERO,
            totalAssets = totalAssets,
            recentLedger = recentLedger,
        )
    }

    /**
     * 미체결 BUY 주문의 예약금. OrderSagaOrchestrator.reserveCash가 제출 시점에 limit_price × 수량을
     * paper_accounts.cash에서 미리 빼 두므로, "사용 가능 현금"에는 빠져 있지만 사용자의 돈이다.
     * LedgerReconciliationService의 불변식(cash + reserved = 초기 + 원장 합)과 같은 정의를 쓴다.
     */
    private fun calcReservedCash(userId: Long): BigDecimal =
        jdbc.queryForObject(RESERVED_CASH_SQL, BigDecimal::class.java, userId) ?: BigDecimal.ZERO

    private fun calcHoldingsValue(userId: Long): BigDecimal {
        // CQRS 읽기모델: portfolio_positions와 최신 가격을 조인해 보유 평가액을 단일 쿼리로 계산한다.
        return jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(pp.net_qty * c.close), 0)
            FROM portfolio_positions pp
            JOIN LATERAL (
                SELECT close FROM candles_1m
                WHERE stock_id = pp.stock_id
                ORDER BY candle_time DESC LIMIT 1
            ) c ON TRUE
            WHERE pp.user_id = ? AND pp.net_qty > 0
            """.trimIndent(),
            BigDecimal::class.java,
            userId,
        ) ?: BigDecimal.ZERO
    }
}
