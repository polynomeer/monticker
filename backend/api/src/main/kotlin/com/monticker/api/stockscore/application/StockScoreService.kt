package com.monticker.api.stockscore.application

import com.monticker.api.common.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 종목 스코어(Simply Wall St "Snowflake" 참고, v1) — ADR-020.
 *
 * 5축(밸류에이션/성장성/과거실적/재무건전성/배당) 중 실데이터로 계산 가능한 건
 * 밸류에이션(PER, stock_fundamentals — ADR-018) 하나뿐이다. 나머지는 스키마에
 * 관련 데이터가 아예 없거나(성장성/재무건전성/배당) candles_1d가 채워지지 않아
 * (과거실적 — 별도 이슈로 분리) 전부 unavailable로 반환한다.
 */
@Service
class StockScoreService(private val jdbc: JdbcTemplate) {

    @Cacheable(cacheNames = [CacheConfig.STOCK_SCORE], key = "#stockId")
    fun getScore(stockId: Long): StockScoreResponse {
        val valuation = computeValuation(stockId)

        val axes = listOf(
            valuation.axis,
            unavailableAxis("GROWTH", "성장성"),
            unavailableAxis("PAST_PERFORMANCE", "과거 실적"),
            unavailableAxis("FINANCIAL_HEALTH", "재무건전성"),
            unavailableAxis("DIVIDENDS", "배당"),
        )

        return StockScoreResponse(
            stockId = stockId,
            axes = axes,
            isValuationPopulationMocked = valuation.populationMocked,
        )
    }

    private fun computeValuation(stockId: Long): ValuationResult {
        val row = jdbc.query(
            """
            WITH pop AS (
                SELECT stock_id, per, is_mocked,
                       PERCENT_RANK() OVER (ORDER BY per ASC) AS pct_rank
                FROM stock_fundamentals
                WHERE per IS NOT NULL AND per > 0
            )
            SELECT
                pop.per, pop.pct_rank,
                (SELECT COUNT(*) FROM pop) AS population_size,
                (SELECT COUNT(*) FROM pop WHERE is_mocked) AS mocked_count
            FROM pop
            WHERE stock_id = ?
            """,
            { rs, _ ->
                ValuationRow(
                    per            = rs.getBigDecimal("per"),
                    pctRank        = rs.getDouble("pct_rank"),
                    populationSize = rs.getInt("population_size"),
                    mockedCount    = rs.getInt("mocked_count"),
                )
            },
            stockId,
        ).firstOrNull()

        if (row == null) {
            return ValuationResult(unavailableAxis("VALUATION", "밸류에이션"), populationMocked = false)
        }

        // 저PER(pct_rank가 낮을수록 저평가)일수록 높은 점수 — 하위 1/3 저평가 → 2점, 상위 1/3 고평가 → 0점
        val score = when {
            row.pctRank < 1.0 / 3 -> 2
            row.pctRank < 2.0 / 3 -> 1
            else                  -> 0
        }
        val cheapPercentile = ((1 - row.pctRank) * 100).toInt()
        val perText = row.per.setScale(1, RoundingMode.HALF_UP)
        val populationMocked = row.populationSize > 0 && row.mockedCount.toDouble() / row.populationSize > 0.5

        return ValuationResult(
            StockScoreAxis(
                axis      = "VALUATION",
                label     = "밸류에이션",
                available = true,
                score     = score,
                detail    = "PER $perText — 비교 종목 대비 저평가 상위 $cheapPercentile%",
            ),
            populationMocked = populationMocked,
        )
    }

    private fun unavailableAxis(axis: String, label: String) =
        StockScoreAxis(axis = axis, label = label, available = false, score = null, detail = null)

    private data class ValuationRow(val per: BigDecimal, val pctRank: Double, val populationSize: Int, val mockedCount: Int)
    private data class ValuationResult(val axis: StockScoreAxis, val populationMocked: Boolean)
}

data class StockScoreAxis(
    val axis: String,
    val label: String,
    val available: Boolean,
    val score: Int?,
    val detail: String?,
)

data class StockScoreResponse(
    val stockId: Long,
    val axes: List<StockScoreAxis>,
    val isValuationPopulationMocked: Boolean,
)
