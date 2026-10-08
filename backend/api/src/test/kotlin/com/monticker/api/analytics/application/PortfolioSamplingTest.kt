package com.monticker.api.analytics.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class PortfolioSamplingTest {

    // 연율화된 3종목 예: 고수익·고위험 / 저수익·저위험 / 중간, 약한 양의 상관
    private val mu = doubleArrayOf(0.20, 0.05, 0.10)
    private val cov = arrayOf(
        doubleArrayOf(0.09, 0.006, 0.01),
        doubleArrayOf(0.006, 0.01, 0.004),
        doubleArrayOf(0.01, 0.004, 0.04),
    )

    @Test
    fun `samples are long-only and sum to one`() {
        val samples = PortfolioSampling.dirichletSamples(assets = 5, count = 2000, seed = 7)

        assertThat(samples).hasSize(2000)
        samples.forEach { w ->
            assertThat(w.sum()).isCloseTo(1.0, within(1e-12))
            assertThat(w.toList()).allMatch { it >= 0.0 }
        }
    }

    @Test
    fun `samples are deterministic for a seed and differ across seeds`() {
        val a = PortfolioSampling.dirichletSamples(4, 50, seed = 42)
        val b = PortfolioSampling.dirichletSamples(4, 50, seed = 42)
        val c = PortfolioSampling.dirichletSamples(4, 50, seed = 43)

        assertThat(a.map { it.toList() }).isEqualTo(b.map { it.toList() })
        assertThat(a.map { it.toList() }).isNotEqualTo(c.map { it.toList() })
    }

    @Test
    fun `sample count above the cap is rejected`() {
        assertThatThrownBy { PortfolioSampling.dirichletSamples(3, PortfolioSampling.MAX_SAMPLES + 1, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `max-Sharpe is at least the Sharpe of every sample and stays on the simplex`() {
        for (rf in listOf(0.0, 0.03)) {
            val samples = PortfolioSampling.dirichletSamples(3, 1000, seed = PortfolioSampling.DEFAULT_SEED)
            val best = PortfolioSampling.maxSharpe(mu, cov, rf, samples)!!

            assertThat(best.weights.sum()).isCloseTo(1.0, within(1e-9))
            assertThat(best.weights.toList()).allMatch { it >= 0.0 }
            val maxSampleSharpe = samples.mapNotNull { PortfolioSampling.evaluate(it, mu, cov, rf).sharpe }.max()
            assertThat(best.sharpe!!).isGreaterThanOrEqualTo(maxSampleSharpe)
        }
    }

    @Test
    fun `refinement reaches the analytic tangency portfolio when it is interior`() {
        // 상관 0이면 접점 비중 ∝ (μ_i − r_f)/σ_i² — 모두 양수라 단체 내부에 있다.
        val diagMu = doubleArrayOf(0.12, 0.08)
        val diagCov = arrayOf(doubleArrayOf(0.04, 0.0), doubleArrayOf(0.0, 0.01))
        val raw = doubleArrayOf(0.12 / 0.04, 0.08 / 0.01)
        val expected = raw.map { it / raw.sum() }

        val best = PortfolioSampling.maxSharpe(diagMu, diagCov, 0.0, listOf(doubleArrayOf(0.5, 0.5)))!!

        assertThat(best.weights[0]).isCloseTo(expected[0], within(1e-4))
        assertThat(best.weights[1]).isCloseTo(expected[1], within(1e-4))
    }

    @Test
    fun `max-Sharpe is null when no start has a defined Sharpe`() {
        val zeroCov = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0))

        assertThat(PortfolioSampling.maxSharpe(doubleArrayOf(0.1, 0.1), zeroCov, 0.0, listOf(doubleArrayOf(0.5, 0.5)))).isNull()
    }

    @Test
    fun `projection onto the simplex is Euclidean and keeps feasible points unchanged`() {
        assertThat(PortfolioSampling.projectOntoSimplex(doubleArrayOf(0.2, 0.3, 0.5)).toList())
            .usingComparatorForType(Comparator<Double> { a, b -> if (Math.abs(a - b) < 1e-12) 0 else a.compareTo(b) }, Double::class.java)
            .containsExactly(0.2, 0.3, 0.5)
        val p = PortfolioSampling.projectOntoSimplex(doubleArrayOf(2.0, 0.0, -1.0))
        assertThat(p.toList()).containsExactly(1.0, 0.0, 0.0)
    }
}
