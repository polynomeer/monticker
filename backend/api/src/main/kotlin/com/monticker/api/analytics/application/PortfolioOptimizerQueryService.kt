package com.monticker.api.analytics.application

import com.monticker.api.backtest.domain.DailyCandle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.sqrt

private const val MAX_STOCK_IDS = 20

@Service
@Transactional(readOnly = true)
class PortfolioOptimizerQueryService(
    private val jdbc: JdbcTemplate,
) {
    private val tradingDaysPerYear = 252.0

    fun optimizeCompute(rawStockIds: List<Long>, targetReturn: Double?): OptimizationResult {
        // V-M5 — 중복 id는 stockIds.indices.associate{...}에서 마지막 것만 남아 조용히 잘못된
        // 결과를 내고, @Cacheable 키가 배열 리터럴이라 순열/중복으로 캐시도 우회한다. 상한이
        // 없으면 종목당 전구간 JDBC 스캔 + O(n²) 공분산 + 500×10 경사하강이 무제한으로 늘어난다.
        val stockIds = rawStockIds.distinct()
        if (stockIds.size < 2) {
            return OptimizationResult(error = "최소 2개 이상의 종목이 필요합니다")
        }
        if (stockIds.size > MAX_STOCK_IDS) {
            return OptimizationResult(error = "종목은 최대 ${MAX_STOCK_IDS}개까지 지정할 수 있습니다")
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

        return OptimizationResult(
            stockIds = stockIds,
            weights = weightMap,
            expectedReturn = expReturn,
            expectedRisk = expRisk,
            currentEqualWeightRisk = eqRisk,
            currentEqualWeightReturn = eqReturn,
            suggestion = suggestion,
        )
    }

    fun getEfficientFrontierCompute(rawStockIds: List<Long>): List<FrontierPoint> {
        val stockIds = rawStockIds.distinct()
        if (stockIds.size < 2 || stockIds.size > MAX_STOCK_IDS) return emptyList()

        val returnsByStock = loadDailyReturns(stockIds)
        val minLen = returnsByStock.values.minOfOrNull { it.size } ?: 0
        if (minLen < 30) return emptyList()

        val aligned = stockIds.map { returnsByStock[it]!!.takeLast(minLen) }
        val mu = aligned.map { it.average() }.toDoubleArray()
        val cov = covarianceMatrix(aligned)

        val minMu = mu.min()
        val maxMu = mu.max()
        val steps = 10
        return (0..steps).map { i ->
            val target = minMu + (maxMu - minMu) * i / steps
            val weights = minimizeVariance(cov, mu, target)
            val expReturn = portfolioReturn(weights, mu) * tradingDaysPerYear
            val expRisk = portfolioRisk(weights, cov) * sqrt(tradingDaysPerYear)
            FrontierPoint(target * tradingDaysPerYear, expReturn, expRisk, stockIds.indices.associate { stockIds[it] to weights[it] })
        }
    }

    /**
     * 분산 최소화 + 목표수익률 페널티(Lagrangian relaxation) — 등식 제약(포트폴리오 기대수익률
     * = targetReturn)이 있는 QP를 투영 경사하강으로 근사한다. targetReturn이 그냥 무시되던 버그
     * 였다(V-M4) — cov 그래디언트만 쓰면 모든 target이 같은 전역 최소분산해로 수렴해 frontier
     * 10점이 동일 가중치가 된다. returnPenaltyLambda는 분산 그래디언트(cov 스케일 ~1e-4)와
     * 페널티 그래디언트(mu 스케일 ~1e-3, 편차 제곱이라 더 작음)가 비슷한 크기로 경합하도록
     * 잡은 경험적 값 — 너무 작으면 target이 여전히 무시되고, 너무 크면 분산 최소화가 무의미해진다.
     */
    fun minimizeVariance(cov: Array<DoubleArray>, mu: DoubleArray, targetReturn: Double, iterations: Int = 500): DoubleArray {
        var w = DoubleArray(mu.size) { 1.0 / mu.size }
        val lr = 0.01
        val returnPenaltyLambda = 500.0
        repeat(iterations) {
            val varianceGrad = DoubleArray(w.size) { i -> 2.0 * (0 until w.size).sumOf { j -> cov[i][j] * w[j] } }
            val returnGap = portfolioReturn(w, mu) - targetReturn
            val returnPenaltyGrad = DoubleArray(w.size) { i -> 2.0 * returnPenaltyLambda * returnGap * mu[i] }
            for (i in w.indices) w[i] -= lr * (varianceGrad[i] + returnPenaltyGrad[i])
            w = projectToSimplex(w)
        }
        return w
    }

    fun projectToSimplex(w: DoubleArray): DoubleArray {
        val clipped = w.map { it.coerceAtLeast(0.0) }.toDoubleArray()
        val sum = clipped.sum()
        return if (sum > 0) clipped.map { it / sum }.toDoubleArray() else DoubleArray(w.size) { 1.0 / w.size }
    }

    private fun portfolioReturn(w: DoubleArray, mu: DoubleArray): Double = w.indices.sumOf { w[it] * mu[it] }

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
                for (k in 0 until len) sum += (returns[i][k] - means[i]) * (returns[j][k] - means[j])
                sum / (len - 1).coerceAtLeast(1)
            }
        }
    }

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

    private fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> =
        jdbc.query(
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
