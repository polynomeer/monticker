package com.monticker.api.analytics.application

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
    val avgPrice: java.math.BigDecimal,
    val currentPrice: java.math.BigDecimal,
    val unrealizedLoss: java.math.BigDecimal,
    val estimatedTaxSaving: java.math.BigDecimal,
)

data class TaxHarvestingResponse(
    val realizedGainYtd: java.math.BigDecimal,
    val candidates: List<HarvestingCandidate>,
    val totalEstimatedTaxSaving: java.math.BigDecimal,
    val taxRateAssumed: java.math.BigDecimal,
    val disclaimer: String = "모의투자 교육용 시뮬레이션입니다. 실제 세무 신고에 사용할 수 없습니다.",
)

@Service
@Transactional(readOnly = true)
class TaxHarvestingQueryService(
    private val jdbc: JdbcTemplate,
) {
    private val taxRate = BigDecimal("0.22")

    fun findCandidates(userId: Long): TaxHarvestingResponse {
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

        if (holdingRows.isEmpty()) {
            return TaxHarvestingResponse(
                realizedGainYtd = realizedGainYtd,
                candidates = emptyList(),
                totalEstimatedTaxSaving = BigDecimal.ZERO,
                taxRateAssumed = taxRate,
            )
        }

        val stockIds = holdingRows.mapNotNull { (it["stock_id"] as? Number)?.toLong() }
        val placeholders = stockIds.joinToString(",") { "?" }

        // Batch price lookup — eliminates N+1
        val priceMap: Map<Long, BigDecimal> = jdbc.queryForList(
            "SELECT DISTINCT ON (stock_id) stock_id, close FROM candles_1m WHERE stock_id IN ($placeholders) ORDER BY stock_id, candle_time DESC",
            *stockIds.toTypedArray()
        ).associate { (it["stock_id"] as Number).toLong() to it["close"] as BigDecimal }

        // Filter to holdings with unrealized loss, then batch-fetch stock info
        data class ValidHolding(val stockId: Long, val qty: Int, val avgPrice: BigDecimal, val currentPrice: BigDecimal)

        val validHoldings = holdingRows.mapNotNull { row ->
            val stockId = (row["stock_id"] as Number).toLong()
            val qty = (row["qty"] as Number).toInt()
            val avgPrice = row["avg_price"] as? BigDecimal ?: return@mapNotNull null
            val currentPrice = priceMap[stockId] ?: return@mapNotNull null
            if (currentPrice >= avgPrice) return@mapNotNull null
            ValidHolding(stockId, qty, avgPrice, currentPrice)
        }

        if (validHoldings.isEmpty()) {
            return TaxHarvestingResponse(
                realizedGainYtd = realizedGainYtd,
                candidates = emptyList(),
                totalEstimatedTaxSaving = BigDecimal.ZERO,
                taxRateAssumed = taxRate,
            )
        }

        // Batch stock info lookup — eliminates N+1
        val validIds = validHoldings.map { it.stockId }
        val infoPlaceholders = validIds.joinToString(",") { "?" }
        val stockInfoMap: Map<Long, Map<String, Any>> = jdbc.queryForList(
            "SELECT id, symbol, name FROM stocks WHERE id IN ($infoPlaceholders)",
            *validIds.toTypedArray()
        ).associate { (it["id"] as Number).toLong() to it }

        val candidates = validHoldings.mapNotNull { h ->
            val info = stockInfoMap[h.stockId] ?: return@mapNotNull null
            val unrealizedLoss = (h.currentPrice - h.avgPrice).multiply(BigDecimal(h.qty))
            val estimatedTaxSaving = unrealizedLoss.abs().min(realizedGainYtd.abs())
                .multiply(taxRate).setScale(2, RoundingMode.HALF_UP)
            HarvestingCandidate(
                stockId = h.stockId,
                symbol = info["symbol"] as String,
                name = info["name"] as String,
                quantity = h.qty,
                avgPrice = h.avgPrice,
                currentPrice = h.currentPrice,
                unrealizedLoss = unrealizedLoss,
                estimatedTaxSaving = estimatedTaxSaving,
            )
        }.sortedByDescending { it.estimatedTaxSaving }

        val totalSaving = candidates.fold(BigDecimal.ZERO) { acc, c -> acc + c.estimatedTaxSaving }

        return TaxHarvestingResponse(
            realizedGainYtd = realizedGainYtd,
            candidates = candidates,
            totalEstimatedTaxSaving = totalSaving,
            taxRateAssumed = taxRate,
        )
    }
}
