package com.monticker.api.wallet.application

import com.monticker.api.paper.infrastructure.PaperAccountRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

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
    private val accountRepo: PaperAccountRepository,
    private val ledgerService: LedgerService,
    private val jdbc: JdbcTemplate,
) {

    fun getWalletMap(userId: Long): WalletMapResponse {
        val account = accountRepo.findByUserId(userId).orElseGet {
            com.monticker.api.paper.domain.PaperAccount(userId = userId)
        }

        val holdingsValue = calcHoldingsValue(userId)
        val reservedCash = calcReservedCash(userId)
        val totalAssets = account.cash.amount + reservedCash + holdingsValue
        val recentLedger = ledgerService.getRecentLedger(userId, 10)

        return WalletMapResponse(
            availableCash = account.cash.amount,
            reservedCash = reservedCash,
            holdingsValue = holdingsValue,
            settlementPending = BigDecimal.ZERO,
            totalAssets = totalAssets,
            recentLedger = recentLedger,
        )
    }

    /** ADR-043 — 미체결 BUY 주문 예약금 (limit_price × 잔량). backend/api WalletService.RESERVED_CASH_SQL과 같은 정의. */
    private fun calcReservedCash(userId: Long): BigDecimal =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(limit_price * (quantity - filled_qty)), 0)
            FROM orders
            WHERE user_id = ? AND side = 'BUY' AND status IN ('PENDING', 'PARTIALLY_FILLED')
            """.trimIndent(),
            BigDecimal::class.java, userId,
        ) ?: BigDecimal.ZERO

    private fun calcHoldingsValue(userId: Long): BigDecimal {
        val rows = jdbc.queryForList(
            """SELECT stock_id,
                      SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) AS net_qty
               FROM paper_trades
               WHERE user_id = ?
               GROUP BY stock_id
               HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
            userId
        )
        var total = BigDecimal.ZERO
        for (row in rows) {
            val stockId = (row["stock_id"] as Number).toLong()
            val qty = (row["net_qty"] as Number).toInt()
            val price = runCatching {
                jdbc.queryForObject(
                    "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                    BigDecimal::class.java, stockId
                )
            }.getOrNull() ?: continue
            total = total.add(price.multiply(BigDecimal(qty)))
        }
        return total
    }
}
