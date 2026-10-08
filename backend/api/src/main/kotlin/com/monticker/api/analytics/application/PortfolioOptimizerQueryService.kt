package com.monticker.api.analytics.application

import com.monticker.api.backtest.domain.DailyCandle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDate
import kotlin.math.sqrt

private const val MAX_STOCK_IDS = 20
private const val MIN_OBSERVATIONS = 30

@Service
@Transactional(readOnly = true)
class PortfolioOptimizerQueryService(
    private val jdbc: JdbcTemplate,
) {
    private val tradingDaysPerYear = 252.0

    fun optimizeCompute(rawStockIds: List<Long>, targetReturn: Double?, period: AnalysisPeriod): OptimizationResult {
        // V-M5 — 중복 id는 stockIds.indices.associate{...}에서 마지막 것만 남아 조용히 잘못된
        // 결과를 내고, @Cacheable 키가 배열 리터럴이라 순열/중복으로 캐시도 우회한다. 상한이
        // 없으면 종목당 전구간 JDBC 스캔 + O(n²) 공분산 + 500×10 경사하강이 무제한으로 늘어난다.
        val stockIds = rawStockIds.distinct()
        validateUniverse(stockIds)?.let { return OptimizationResult(error = it) }

        val stats = when (val loaded = loadStats(stockIds, period)) {
            is Loaded.Ok -> loaded.stats
            is Loaded.Insufficient -> return OptimizationResult(error = loaded.message)
        }
        val mu = stats.mu
        val cov = stats.cov

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
            period = stats.periodInfo(period),
        )
    }

    /**
     * 주어진 비중의 연 기대수익·연 위험. 최적화와 같은 데이터(같은 분석 기간, 모든 종목이 함께 거래된 날)로
     * 계산해 최적 비중과 같은 축에서 비교할 수 있게 한다. 데이터가 부족하면 null.
     */
    fun evaluateWeights(rawStockIds: List<Long>, weights: Map<Long, Double>, period: AnalysisPeriod): Pair<Double, Double>? {
        val stockIds = rawStockIds.distinct()
        if (stockIds.isEmpty() || stockIds.size > MAX_STOCK_IDS) return null
        val stats = (loadStats(stockIds, period) as? Loaded.Ok)?.stats ?: return null
        val w = DoubleArray(stockIds.size) { weights[stockIds[it]] ?: 0.0 }
        return portfolioReturn(w, stats.mu) * tradingDaysPerYear to portfolioRisk(w, stats.cov) * sqrt(tradingDaysPerYear)
    }

    /**
     * 효율적 프론티어(목표수익 11점) + 무작위 롱 온리 포트폴리오 표본 + 샤프 비율 최대 지점(ADR-097).
     * 입력 오류·데이터 부족은 [IllegalArgumentException](→ 400).
     */
    fun frontierAnalysisCompute(
        rawStockIds: List<Long>,
        period: AnalysisPeriod,
        sampleCount: Int,
        riskFreeRate: Double,
        seed: Long = PortfolioSampling.DEFAULT_SEED,
    ): FrontierAnalysis {
        val stockIds = rawStockIds.distinct()
        validateUniverse(stockIds)?.let { throw IllegalArgumentException(it) }
        require(sampleCount in PortfolioSampling.MIN_SAMPLES..PortfolioSampling.MAX_SAMPLES) {
            "표본 수는 ${PortfolioSampling.MIN_SAMPLES}~${PortfolioSampling.MAX_SAMPLES} 사이여야 합니다"
        }
        val stats = when (val loaded = loadStats(stockIds, period)) {
            is Loaded.Ok -> loaded.stats
            is Loaded.Insufficient -> throw IllegalArgumentException(loaded.message)
        }

        val frontier = frontierPoints(stockIds, stats.mu, stats.cov)

        // 표본·샤프는 연율화한 값으로 계산한다(무위험 수익률이 연 단위라서).
        val muAnn = DoubleArray(stats.mu.size) { stats.mu[it] * tradingDaysPerYear }
        val covAnn = Array(stats.cov.size) { i -> DoubleArray(stats.cov.size) { j -> stats.cov[i][j] * tradingDaysPerYear } }
        val n = stockIds.size
        val sampleWeights = PortfolioSampling.dirichletSamples(n, sampleCount, seed)
        val samples = sampleWeights.map {
            val e = PortfolioSampling.evaluate(it, muAnn, covAnn, riskFreeRate)
            SamplePoint(e.expectedReturn, e.expectedRisk, e.sharpe)
        }

        // 출발점: 표본 전부 + 동일가중 + 단일 종목 꼭짓점 + 프론티어 점. 결과 샤프는 이들 중 최대 이상이다.
        val starts = buildList {
            addAll(sampleWeights)
            add(DoubleArray(n) { 1.0 / n })
            for (k in 0 until n) add(DoubleArray(n) { if (it == k) 1.0 else 0.0 })
            frontier.forEach { p -> add(DoubleArray(n) { p.weights[stockIds[it]] ?: 0.0 }) }
        }
        val best = PortfolioSampling.maxSharpe(muAnn, covAnn, riskFreeRate, starts)

        return FrontierAnalysis(
            stockIds = stockIds,
            period = stats.periodInfo(period),
            frontier = frontier,
            samples = samples,
            maxSharpe = best?.let {
                MaxSharpePoint(
                    weights = stockIds.indices.associate { i -> stockIds[i] to it.weights[i] },
                    expectedReturn = it.expectedReturn,
                    expectedRisk = it.expectedRisk,
                    sharpe = it.sharpe!!,
                )
            },
            riskFreeRate = riskFreeRate,
            seed = seed,
        )
    }

    private fun frontierPoints(stockIds: List<Long>, mu: DoubleArray, cov: Array<DoubleArray>): List<FrontierPoint> {
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

    private fun validateUniverse(stockIds: List<Long>): String? = when {
        stockIds.size < 2 -> "최소 2개 이상의 종목이 필요합니다"
        stockIds.size > MAX_STOCK_IDS -> "종목은 최대 ${MAX_STOCK_IDS}개까지 지정할 수 있습니다"
        else -> null
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

    private fun portfolioReturn(w: DoubleArray, mu: DoubleArray): Double = PortfolioSampling.portfolioReturn(w, mu)

    private fun portfolioRisk(w: DoubleArray, cov: Array<DoubleArray>): Double = PortfolioSampling.portfolioRisk(w, cov)

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

    private class Stats(
        val mu: DoubleArray,
        val cov: Array<DoubleArray>,
        val observations: Int,
        val firstDate: LocalDate,
        val lastDate: LocalDate,
    ) {
        fun periodInfo(p: AnalysisPeriod) = PeriodInfo(p.label, p.from, p.to, firstDate, lastDate, observations)
    }

    private sealed interface Loaded {
        class Ok(val stats: Stats) : Loaded
        class Insufficient(val message: String) : Loaded
    }

    /**
     * 분석 기간 안에서 **모든 종목이 함께 종가를 가진 날**만 모아 일간 수익률을 만든다. 예전에는 종목마다
     * 마지막 N개를 잘라 맞췄기 때문에 휴장일이 다른 종목(국내·해외)의 날짜가 어긋났다.
     * 행 수는 기간 상한(3년 ≈ 일봉 1,100개)으로 묶인다.
     */
    private fun loadStats(stockIds: List<Long>, period: AnalysisPeriod): Loaded {
        val closesByStock = stockIds.map { id ->
            loadDailyCandles(id, period).filter { it.close.signum() > 0 }.associate { it.date to it.close.toDouble() }
        }
        val commonSet = closesByStock.first().keys.toSortedSet()
        closesByStock.drop(1).forEach { commonSet.retainAll(it.keys) }
        val common = commonSet.toList()
        val observations = (common.size - 1).coerceAtLeast(0)
        if (observations < MIN_OBSERVATIONS) {
            return Loaded.Insufficient(
                "데이터 부족: 분석 기간(${period.from}~${period.to})에 모든 종목이 함께 거래된 수익률이 ${observations}일입니다. " +
                    "최소 ${MIN_OBSERVATIONS}일이 필요합니다 — 기간을 늘리거나 종목을 바꿔 보세요",
            )
        }
        val aligned = closesByStock.map { closes ->
            (1 until common.size).map { k ->
                val prev = closes.getValue(common[k - 1])
                (closes.getValue(common[k]) - prev) / prev
            }
        }
        return Loaded.Ok(
            Stats(
                mu = aligned.map { it.average() }.toDoubleArray(),
                cov = covarianceMatrix(aligned),
                observations = observations,
                firstDate = common.first(),
                lastDate = common.last(),
            ),
        )
    }

    private fun loadDailyCandles(stockId: Long, period: AnalysisPeriod): List<DailyCandle> =
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
              AND candle_time >= ?
              AND candle_time < ?
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
            // KST 경계는 Kotlin에서 계산하고 Timestamp로 감싸 바인딩한다(Instant 직접 바인딩 금지).
            stockId, Timestamp.from(period.range.start), Timestamp.from(period.range.endExclusive),
        )
}
