package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.domain.TaxHarvestingLog
import com.monticker.api.analytics.infrastructure.TaxHarvestingLogRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

data class HarvestingCandidate(
    val stockId: Long,
    val symbol: String,
    val name: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val unrealizedLoss: BigDecimal,
    val estimatedTaxSaving: BigDecimal,
)

data class TaxHarvestingResponse(
    val realizedGainYtd: BigDecimal,
    val candidates: List<HarvestingCandidate>,
    val totalEstimatedTaxSaving: BigDecimal,
    val taxRateAssumed: BigDecimal,
    val disclaimer: String = "모의투자 교육용 시뮬레이션입니다. 실제 세무 신고에 사용할 수 없습니다.",
)

@Service
class TaxOptimizerService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val taxHarvestingLogRepository: TaxHarvestingLogRepository,
) {
    private val taxRate = BigDecimal("0.22")

    @Transactional
    fun findHarvestingCandidates(userId: Long): TaxHarvestingResponse {
        val holdingRows = jdbc.queryForList(
            """SELECT stock_id,
                      SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) AS qty,
                      SUM(CASE WHEN side='BUY' THEN amount ELSE 0 END) /
                          NULLIF(SUM(CASE WHEN side='BUY' THEN quantity ELSE 0 END), 0) AS avg_price
               FROM paper_trades WHERE user_id = ?
               GROUP BY stock_id
               HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
            userId
        )

        // Simplification: realized YTD gain approximated as
        // SUM(sell amount) - SUM(matching buy cost at average buy price for that stock) for sells this year.
        // This is not FIFO-accurate but is a reasonable approximation for a paper-trading educational tool.
        val realizedGainYtd = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(s.amount - (b.avg_buy_price * s.quantity)), 0)
            FROM paper_trades s
            JOIN (
                SELECT stock_id, AVG(price) AS avg_buy_price
                FROM paper_trades WHERE user_id = ? AND side = 'BUY'
                GROUP BY stock_id
            ) b ON b.stock_id = s.stock_id
            WHERE s.user_id = ? AND s.side = 'SELL'
              AND s.traded_at >= date_trunc('year', now())
            """.trimIndent(),
            BigDecimal::class.java, userId, userId
        ) ?: BigDecimal.ZERO

        val candidates = holdingRows.mapNotNull { row ->
            val stockId = (row["stock_id"] as Number).toLong()
            val qty = (row["qty"] as Number).toInt()
            val avgPrice = row["avg_price"] as? BigDecimal ?: return@mapNotNull null
            val currentPrice = runCatching {
                jdbc.queryForObject(
                    "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                    BigDecimal::class.java, stockId
                )
            }.getOrNull() ?: return@mapNotNull null

            if (currentPrice >= avgPrice) return@mapNotNull null

            val unrealizedLoss = (currentPrice - avgPrice).multiply(BigDecimal(qty))
            val estimatedTaxSaving = unrealizedLoss.abs().min(realizedGainYtd.abs())
                .multiply(taxRate).setScale(2, RoundingMode.HALF_UP)

            val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", stockId)

            HarvestingCandidate(
                stockId = stockId,
                symbol = info["symbol"] as String,
                name = info["name"] as String,
                quantity = qty,
                avgPrice = avgPrice,
                currentPrice = currentPrice,
                unrealizedLoss = unrealizedLoss,
                estimatedTaxSaving = estimatedTaxSaving,
            )
        }.sortedByDescending { it.estimatedTaxSaving }

        val totalSaving = candidates.fold(BigDecimal.ZERO) { acc, c -> acc + c.estimatedTaxSaving }

        val log = TaxHarvestingLog(
            userId = userId,
            realizedGainYtd = realizedGainYtd,
            candidatesJson = objectMapper.writeValueAsString(candidates),
            estimatedTaxSaving = totalSaving,
            taxRateAssumed = taxRate,
        )
        taxHarvestingLogRepository.save(log)

        return TaxHarvestingResponse(
            realizedGainYtd = realizedGainYtd,
            candidates = candidates,
            totalEstimatedTaxSaving = totalSaving,
            taxRateAssumed = taxRate,
        )
    }
}
