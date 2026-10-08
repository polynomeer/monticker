package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.domain.PortfolioOptimization
import com.monticker.api.analytics.infrastructure.PortfolioOptimizationRepository
import com.monticker.api.common.cache.CacheConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

data class FrontierPoint(
    val targetReturn: Double,
    val expectedReturn: Double,
    val expectedRisk: Double,
    val weights: Map<Long, Double>,
)

/** 실제로 계산에 쓴 기간. [firstDate]~[lastDate]는 모든 종목이 함께 거래된 첫·마지막 날(KST). */
data class PeriodInfo(
    val period: String,
    val from: LocalDate,
    val to: LocalDate,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    /** 계산에 쓴 일간 수익률 개수 */
    val observations: Int,
)

/** 무작위 롱 온리 포트폴리오 하나의 연 기대수익·연 변동성·샤프. 변동성이 0이면 sharpe는 null. */
data class SamplePoint(val expectedReturn: Double, val expectedRisk: Double, val sharpe: Double?)

/** 샤프 비율 최대 지점(과거 데이터 기준). 투자 권유가 아니라 분석 결과다. */
data class MaxSharpePoint(
    val weights: Map<Long, Double>,
    val expectedReturn: Double,
    val expectedRisk: Double,
    val sharpe: Double,
)

data class FrontierAnalysis(
    val stockIds: List<Long>,
    val period: PeriodInfo,
    val frontier: List<FrontierPoint>,
    val samples: List<SamplePoint>,
    val maxSharpe: MaxSharpePoint?,
    /** 샤프 계산에 쓴 연 무위험 수익률(app.analytics.risk-free-rate) */
    val riskFreeRate: Double,
    val seed: Long,
    val method: String = METHOD,
) {
    companion object {
        const val METHOD = "dirichlet-sample+projected-gradient-ascent"
    }
}

data class OptimizationResult(
    val stockIds: List<Long> = emptyList(),
    val weights: Map<Long, Double> = emptyMap(),
    val expectedReturn: Double = 0.0,
    val expectedRisk: Double = 0.0,
    val currentEqualWeightRisk: Double = 0.0,
    val currentEqualWeightReturn: Double = 0.0,
    val suggestion: String = "",
    val error: String? = null,
    val period: PeriodInfo? = null,
)

@Service
class PortfolioOptimizerService(
    private val queryService: PortfolioOptimizerQueryService,
    private val objectMapper: ObjectMapper,
    private val optimizationRepository: PortfolioOptimizationRepository,
    /**
     * 샤프 비율의 연 무위험 수익률(소수, 0.03 = 3%). 기본 0 — 국내·해외 종목을 섞어 분석하므로 특정 통화의
     * 금리를 가정하지 않는다. 운영에서 바꾸려면 `ANALYTICS_RISK_FREE_RATE`를 준다. ADR-097.
     */
    @Value("\${app.analytics.risk-free-rate:0.0}") private val riskFreeRate: Double = 0.0,
) {
    init {
        require(riskFreeRate in -0.05..0.2) { "app.analytics.risk-free-rate는 -0.05~0.2 사이여야 합니다: $riskFreeRate" }
    }

    @Cacheable(
        cacheNames = [CacheConfig.PORTFOLIO_OPTIMIZER],
        key = "#userId + ':' + T(java.util.Arrays).toString(#stockIds.toArray()) + ':' + #targetReturn + ':' + #period.from + ':' + #period.to",
    )
    @Transactional
    fun optimize(userId: Long, stockIds: List<Long>, targetReturn: Double?, period: AnalysisPeriod): OptimizationResult {
        val result = queryService.optimizeCompute(stockIds, targetReturn, period)
        if (result.error != null) return result

        optimizationRepository.save(
            PortfolioOptimization(
                userId = userId,
                targetReturn = BigDecimal.valueOf(targetReturn ?: result.weights.values.average()),
                universeJson = objectMapper.writeValueAsString(stockIds),
                weightsJson = objectMapper.writeValueAsString(result.weights),
                expectedReturn = BigDecimal.valueOf(result.expectedReturn),
                expectedRisk = BigDecimal.valueOf(result.expectedRisk),
            )
        )

        return result
    }

    /** 프론티어·무작위 표본·샤프 최대 지점. 입력 오류·데이터 부족은 IllegalArgumentException(→ 400). */
    @Transactional
    fun getEfficientFrontier(
        userId: Long,
        stockIds: List<Long>,
        period: AnalysisPeriod,
        sampleCount: Int = PortfolioSampling.DEFAULT_SAMPLES,
    ): FrontierAnalysis {
        val analysis = queryService.frontierAnalysisCompute(stockIds, period, sampleCount, riskFreeRate)

        optimizationRepository.save(
            PortfolioOptimization(
                userId = userId,
                targetReturn = null,
                universeJson = objectMapper.writeValueAsString(analysis.stockIds),
                weightsJson = objectMapper.writeValueAsString(
                    analysis.maxSharpe?.weights ?: analysis.frontier.lastOrNull()?.weights ?: emptyMap<Long, Double>(),
                ),
                expectedReturn = analysis.maxSharpe?.let { BigDecimal.valueOf(it.expectedReturn) },
                expectedRisk = analysis.maxSharpe?.let { BigDecimal.valueOf(it.expectedRisk) },
                frontierJson = objectMapper.writeValueAsString(analysis.frontier),
            )
        )

        return analysis
    }
}
