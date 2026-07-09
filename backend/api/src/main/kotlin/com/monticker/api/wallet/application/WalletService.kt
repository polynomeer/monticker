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
        val totalAssets = account.cash.amount + holdingsValue
        val recentLedger = ledgerService.getLedger(userId).take(10)

        return WalletMapResponse(
            availableCash = account.cash.amount,
            reservedCash = BigDecimal.ZERO,
            holdingsValue = holdingsValue,
            settlementPending = BigDecimal.ZERO,
            totalAssets = totalAssets,
            recentLedger = recentLedger,
        )
    }

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
