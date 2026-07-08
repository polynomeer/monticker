package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.domain.PortfolioOptimization
import com.monticker.api.analytics.infrastructure.PortfolioOptimizationRepository
import com.monticker.api.common.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

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
    private val queryService: PortfolioOptimizerQueryService,
    private val objectMapper: ObjectMapper,
    private val optimizationRepository: PortfolioOptimizationRepository,
) {
    @Cacheable(
        cacheNames = [CacheConfig.PORTFOLIO_OPTIMIZER],
        key = "#userId + ':' + T(java.util.Arrays).toString(#stockIds.toArray()) + ':' + #targetReturn",
    )
    @Transactional
    fun optimize(userId: Long, stockIds: List<Long>, targetReturn: Double?): OptimizationResult {
        val result = queryService.optimizeCompute(stockIds, targetReturn)
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

    @Transactional
    fun getEfficientFrontier(userId: Long, stockIds: List<Long>): List<FrontierPoint> {
        val points = queryService.getEfficientFrontierCompute(stockIds)
        if (points.isEmpty()) return emptyList()

        optimizationRepository.save(
            PortfolioOptimization(
                userId = userId,
                targetReturn = null,
                universeJson = objectMapper.writeValueAsString(stockIds),
                weightsJson = objectMapper.writeValueAsString(points.lastOrNull()?.weights ?: emptyMap<Long, Double>()),
                expectedReturn = null,
                expectedRisk = null,
                frontierJson = objectMapper.writeValueAsString(points),
            )
        )

        return points
    }
}
