package com.monticker.api.analytics.application

import com.monticker.api.analytics.domain.RegimeHistory
import com.monticker.api.analytics.infrastructure.RegimeHistoryRepository
import com.monticker.api.common.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

data class RegimeResult(
    val regime: String,
    val adx: Double,
    val volatility: Double,
    val trendSlope: Double,
    val explanation: String,
    val error: String? = null,
    val detectedAt: LocalDate? = null,
)

@Service
class RegimeDetectorService(
    private val queryService: RegimeDetectorQueryService,
    private val regimeHistoryRepository: RegimeHistoryRepository,
) {
    @Cacheable(cacheNames = [CacheConfig.REGIME], key = "'stock:' + #stockId")
    @Transactional
    fun classifyRegime(stockId: Long): RegimeResult {
        val result = queryService.classifyRegimeCompute(stockId)
        if (result.error != null) return result

        val today = result.detectedAt ?: LocalDate.now()
        if (regimeHistoryRepository.findByStockIdAndRegimeDate(stockId, today) == null) {
            regimeHistoryRepository.save(
                RegimeHistory(
                    stockId = stockId,
                    market = null,
                    regimeDate = today,
                    regime = result.regime,
                    adx = BigDecimal.valueOf(result.adx),
                    volatility = BigDecimal.valueOf(result.volatility),
                    trendSlope = BigDecimal.valueOf(result.trendSlope),
                )
            )
        }
        return result
    }

    @Cacheable(cacheNames = [CacheConfig.REGIME], key = "'market:' + #market")
    @Transactional
    fun classifyMarketRegime(market: String): RegimeResult {
        val result = queryService.classifyMarketRegimeCompute(market)
        if (result.error != null) return result

        val today = result.detectedAt ?: LocalDate.now()
        if (regimeHistoryRepository.findByMarketAndRegimeDate(market, today) == null) {
            regimeHistoryRepository.save(
                RegimeHistory(
                    stockId = null,
                    market = market,
                    regimeDate = today,
                    regime = result.regime,
                    adx = BigDecimal.valueOf(result.adx),
                    volatility = BigDecimal.valueOf(result.volatility),
                    trendSlope = BigDecimal.valueOf(result.trendSlope),
                )
            )
        }
        return result
    }
}
