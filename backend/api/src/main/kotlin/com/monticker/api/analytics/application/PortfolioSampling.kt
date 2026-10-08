package com.monticker.api.analytics.application

import java.util.SplittableRandom
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 무작위 포트폴리오 표본과 샤프 비율 최대 지점(ADR-097). 순수 계산만 한다 — DB·스프링 의존 없음.
 *
 * 입력 `mu`·`cov`는 **연율화**된 기대수익·공분산이다. 모든 비중은 롱 온리(≥ 0)이고 합이 1이다.
 * 과거 데이터로 계산한 분석 결과일 뿐 미래 성과를 보장하지 않는다.
 */
object PortfolioSampling {

    const val DEFAULT_SEED = 20_261_008L
    const val MIN_SAMPLES = 100
    const val MAX_SAMPLES = 2000
    const val DEFAULT_SAMPLES = 1000

    /** 표본 비중이 이보다 작은 변동성이면 샤프를 정의하지 않는다(0으로 나누기 방지). */
    private const val MIN_RISK = 1e-12
    private const val MAX_ITERATIONS = 500
    private const val MIN_STEP = 1e-12
    private const val MAX_STEP = 1e3

    data class Evaluated(val weights: DoubleArray, val expectedReturn: Double, val expectedRisk: Double, val sharpe: Double?)

    /**
     * 단체(simplex) 위 균등분포 = Dirichlet(1,…,1) 표본. 각 성분을 −ln U(U∈(0,1])로 뽑아 합으로 나눈다.
     * 같은 seed면 같은 표본이 나온다(SplittableRandom은 JVM·플랫폼과 무관하게 결정적).
     */
    fun dirichletSamples(assets: Int, count: Int, seed: Long): List<DoubleArray> {
        require(assets >= 1) { "assets must be ≥ 1" }
        require(count in 0..MAX_SAMPLES) { "count must be within 0..$MAX_SAMPLES" }
        val rnd = SplittableRandom(seed)
        return List(count) {
            val raw = DoubleArray(assets) { -ln(1.0 - rnd.nextDouble()) } // 1 - [0,1) = (0,1]
            val sum = raw.sum()
            if (sum > 0.0) DoubleArray(assets) { i -> raw[i] / sum } else DoubleArray(assets) { 1.0 / assets }
        }
    }

    fun portfolioReturn(w: DoubleArray, mu: DoubleArray): Double {
        var s = 0.0
        for (i in w.indices) s += w[i] * mu[i]
        return s
    }

    fun portfolioRisk(w: DoubleArray, cov: Array<DoubleArray>): Double {
        var v = 0.0
        for (i in w.indices) for (j in w.indices) v += w[i] * cov[i][j] * w[j]
        return sqrt(v.coerceAtLeast(0.0))
    }

    fun evaluate(w: DoubleArray, mu: DoubleArray, cov: Array<DoubleArray>, riskFreeRate: Double): Evaluated {
        val r = portfolioReturn(w, mu)
        val s = portfolioRisk(w, cov)
        return Evaluated(w, r, s, if (s > MIN_RISK) (r - riskFreeRate) / s else null)
    }

    /**
     * 샤프 비율 최대 지점. [starts](무작위 표본·동일가중·프론티어 점 등) 중 샤프가 가장 큰 점에서 출발해
     * 투영 경사상승(단체 위 유클리드 투영 + 백트래킹)으로 다듬는다. 샤프가 **엄격히 오를 때만** 이동하므로
     * 결과의 샤프는 [starts] 어느 점보다도 작지 않다. 반복은 [MAX_ITERATIONS]회로 묶여 있다.
     * 샤프를 정의할 수 있는 시작점이 없으면 null.
     */
    fun maxSharpe(mu: DoubleArray, cov: Array<DoubleArray>, riskFreeRate: Double, starts: List<DoubleArray>): Evaluated? {
        var best = starts.asSequence()
            .map { evaluate(it, mu, cov, riskFreeRate) }
            .filter { it.sharpe != null }
            .maxByOrNull { it.sharpe!! } ?: return null

        var step = 1.0
        for (iter in 0 until MAX_ITERATIONS) {
            val g = sharpeGradient(best.weights, mu, cov, riskFreeRate) ?: break
            var t = step
            var moved = false
            while (t >= MIN_STEP) {
                val candidate = projectOntoSimplex(DoubleArray(g.size) { i -> best.weights[i] + t * g[i] })
                val e = evaluate(candidate, mu, cov, riskFreeRate)
                if (e.sharpe != null && e.sharpe > best.sharpe!!) {
                    val gain = e.sharpe - best.sharpe
                    best = e
                    step = (t * 2).coerceAtMost(MAX_STEP)
                    moved = gain > 1e-12
                    break
                }
                t /= 2
            }
            if (!moved) break
        }
        return best
    }

    /** ∂S/∂w = μ/σ − (wᵀμ − r_f)·Σw/σ³. σ가 0에 가까우면 null. */
    private fun sharpeGradient(w: DoubleArray, mu: DoubleArray, cov: Array<DoubleArray>, rf: Double): DoubleArray? {
        val sigma = portfolioRisk(w, cov)
        if (sigma <= MIN_RISK) return null
        val excess = portfolioReturn(w, mu) - rf
        val sigma3 = sigma * sigma * sigma
        return DoubleArray(w.size) { i ->
            var covW = 0.0
            for (j in w.indices) covW += cov[i][j] * w[j]
            mu[i] / sigma - excess * covW / sigma3
        }
    }

    /** 확률 단체 {w ≥ 0, Σw = 1} 위로의 유클리드 투영(Duchi et al., 2008). */
    fun projectOntoSimplex(v: DoubleArray): DoubleArray {
        val u = v.sortedArrayDescending()
        var cumulative = 0.0
        var theta = 0.0
        for (j in u.indices) {
            cumulative += u[j]
            val t = (cumulative - 1.0) / (j + 1)
            if (u[j] - t > 0) theta = t
        }
        val w = DoubleArray(v.size) { i -> (v[i] - theta).coerceAtLeast(0.0) }
        val sum = w.sum()
        // 부동소수 오차 보정 — 합을 정확히 1로 맞춘다.
        return if (sum > 0.0) DoubleArray(w.size) { i -> w[i] / sum } else DoubleArray(v.size) { 1.0 / v.size }
    }
}
