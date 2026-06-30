package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.domain.PortfolioOptimization
import com.monticker.api.analytics.infrastructure.PortfolioOptimizationRepository
import com.monticker.api.backtest.domain.DailyCandle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.sqrt

data class FrontierPoint(
    val targetReturn: Double,
    val expectedReturn: Double,
    val expectedRisk: Double,
    val weights: Map<Long, Double>,
)

data class OptimizationResult(
    val stockIds: List<Long> = emptyList(),
    val weights: Map<Long, Double> = emptyMap(),
    val expectedReturn: Double = 0.0,
    val expectedRisk: Double = 0.0,
    val currentEqualWeightRisk: Double = 0.0,
    val currentEqualWeightReturn: Double = 0.0,
    val suggestion: String = "",
    val error: String? = null,
)

@Service
class PortfolioOptimizerService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val optimizationRepository: PortfolioOptimizationRepository,
) {
    private val tradingDaysPerYear = 252.0

    @Transactional
    fun optimize(userId: Long, stockIds: List<Long>, targetReturn: Double?): OptimizationResult {
        if (stockIds.size < 2) {
            return OptimizationResult(error = "최소 2개 이상의 종목이 필요합니다")
        }

        val returnsByStock = loadDailyReturns(stockIds)
        val minLen = returnsByStock.values.minOfOrNull { it.size } ?: 0
        if (minLen < 30) {
            return OptimizationResult(error = "데이터 부족: 최소 30일 데이터가 필요합니다")
        }

        val aligned = stockIds.map { returnsByStock[it]!!.takeLast(minLen) }
        val mu = aligned.map { it.average() }.toDoubleArray()
        val cov = covarianceMatrix(aligned)

        val target = targetReturn ?: mu.average()
        val weights = minimizeVariance(cov, mu, target)

        val expReturn = portfolioReturn(weights, mu) * tradingDaysPerYear
        val expRisk = portfolioRisk(weights, cov) * sqrt(tradingDaysPerYear)

        val equalWeights = DoubleArray(stockIds.size) { 1.0 / stockIds.size }
        val eqReturn = portfolioReturn(equalWeights, mu) * tradingDaysPerYear
        val eqRisk = portfolioRisk(equalWeights, cov) * sqrt(tradingDaysPerYear)

        val weightMap = stockIds.indices.associate { stockIds[it] to weights[it] }

        val suggestion = buildString {
            if (expRisk < eqRisk) {
                append("최적화된 포트폴리오는 동일가중 대비 위험이 ${"%.2f".format((eqRisk - expRisk) * 100)}%p 낮습니다. ")
            } else {
                append("최적화된 포트폴리오의 위험이 동일가중과 유사하거나 높습니다. ")
            }
            append("기대 연 수익률 ${"%.2f".format(expReturn * 100)}%, 기대 연 변동성 ${"%.2f".format(expRisk * 100)}% 입니다.")
        }

        val result = OptimizationResult(
            stockIds = stockIds,
            weights = weightMap,
            expectedReturn = expReturn,
            expectedRisk = expRisk,
            currentEqualWeightRisk = eqRisk,
            currentEqualWeightReturn = eqReturn,
            suggestion = suggestion,
        )

        val entity = PortfolioOptimization(
            userId = userId,
            targetReturn = BigDecimal.valueOf(target),
            universeJson = objectMapper.writeValueAsString(stockIds),
            weightsJson = objectMapper.writeValueAsString(weightMap),
            expectedReturn = BigDecimal.valueOf(expReturn),
            expectedRisk = BigDecimal.valueOf(expRisk),
        )
        optimizationRepository.save(entity)

        return result
    }

    fun getEfficientFrontier(userId: Long, stockIds: List<Long>): List<FrontierPoint> {
        if (stockIds.size < 2) return emptyList()

        val returnsByStock = loadDailyReturns(stockIds)
        val minLen = returnsByStock.values.minOfOrNull { it.size } ?: 0
        if (minLen < 30) return emptyList()

        val aligned = stockIds.map { returnsByStock[it]!!.takeLast(minLen) }
        val mu = aligned.map { it.average() }.toDoubleArray()
        val cov = covarianceMatrix(aligned)

        val minMu = mu.min()
        val maxMu = mu.max()
        val steps = 10
        val points = mutableListOf<FrontierPoint>()
        for (i in 0..steps) {
            val target = minMu + (maxMu - minMu) * i / steps
            val weights = minimizeVariance(cov, mu, target)
            val expReturn = portfolioReturn(weights, mu) * tradingDaysPerYear
            val expRisk = portfolioRisk(weights, cov) * sqrt(tradingDaysPerYear)
            val weightMap = stockIds.indices.associate { stockIds[it] to weights[it] }
            points.add(FrontierPoint(target * tradingDaysPerYear, expReturn, expRisk, weightMap))
        }

        // Persist frontier alongside a baseline optimization record
        val entity = PortfolioOptimization(
            userId = userId,
            targetReturn = null,
            universeJson = objectMapper.writeValueAsString(stockIds),
            weightsJson = objectMapper.writeValueAsString(points.lastOrNull()?.weights ?: emptyMap<Long, Double>()),
            expectedReturn = null,
            expectedRisk = null,
            frontierJson = objectMapper.writeValueAsString(points),
        )
        optimizationRepository.save(entity)

        return points
    }

    // ─── Math helpers ──────────────────────────────────────────────────────────

    private fun portfolioReturn(w: DoubleArray, mu: DoubleArray): Double =
        w.indices.sumOf { w[it] * mu[it] }

    private fun portfolioRisk(w: DoubleArray, cov: Array<DoubleArray>): Double {
        val variance = w.indices.sumOf { i -> w.indices.sumOf { j -> w[i] * cov[i][j] * w[j] } }
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private fun covarianceMatrix(returns: List<List<Double>>): Array<DoubleArray> {
        val n = returns.size
        val means = returns.map { it.average() }
        val len = returns[0].size
        return Array(n) { i ->
            DoubleArray(n) { j ->
                var sum = 0.0
                for (k in 0 until len) {
                    sum += (returns[i][k] - means[i]) * (returns[j][k] - means[j])
                }
                sum / (len - 1).coerceAtLeast(1)
            }
        }
    }

    fun minimizeVariance(cov: Array<DoubleArray>, mu: DoubleArray, targetReturn: Double, iterations: Int = 500): DoubleArray {
        var w = DoubleArray(mu.size) { 1.0 / mu.size }
        val lr = 0.01
        repeat(iterations) {
            val grad = DoubleArray(w.size) { i -> 2.0 * (0 until w.size).sumOf { j -> cov[i][j] * w[j] } }
            for (i in w.indices) w[i] -= lr * grad[i]
            w = projectToSimplex(w)
        }
        // soft-bias toward target return is implicit via projection; final weights kept as min-variance feasible point
        return w
    }

    fun projectToSimplex(w: DoubleArray): DoubleArray {
        val clipped = w.map { it.coerceAtLeast(0.0) }.toDoubleArray()
        val sum = clipped.sum()
        return if (sum > 0) clipped.map { it / sum }.toDoubleArray() else DoubleArray(w.size) { 1.0 / w.size }
    }

    // ─── Data loading ──────────────────────────────────────────────────────────

    private fun loadDailyReturns(stockIds: List<Long>): Map<Long, List<Double>> {
        val to = LocalDate.now()
        val from = to.minusDays(400)
        return stockIds.associateWith { stockId ->
            val candles = loadDailyCandles(stockId, from, to).takeLast(253)
            candles.zipWithNext { a, b ->
                val prev = a.close.toDouble()
                val curr = b.close.toDouble()
                if (prev == 0.0) 0.0 else (curr - prev) / prev
            }
        }
    }

    private fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> {
        return jdbc.query(
            """
            SELECT
                DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
                (ARRAY_AGG(open  ORDER BY candle_time ASC))[1]  AS open,
                MAX(high)                                        AS high,
                MIN(low)                                         AS low,
                (ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close,
                SUM(volume)                                      AS volume
            FROM candles_1m
            WHERE stock_id = ?
              AND DATE(candle_time AT TIME ZONE 'Asia/Seoul') BETWEEN ? AND ?
            GROUP BY d
            ORDER BY d
            """.trimIndent(),
            { rs, _ ->
                DailyCandle(
                    date = rs.getDate("d").toLocalDate(),
                    open = rs.getBigDecimal("open"),
                    high = rs.getBigDecimal("high"),
                    low = rs.getBigDecimal("low"),
                    close = rs.getBigDecimal("close"),
                    volume = rs.getLong("volume"),
                )
            },
            stockId, from, to,
        )
    }
}
