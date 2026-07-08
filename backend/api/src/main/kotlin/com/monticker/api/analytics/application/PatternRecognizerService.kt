package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.domain.DetectedPattern
import com.monticker.api.analytics.infrastructure.DetectedPatternRepository
import com.monticker.api.common.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

enum class SwingType { HIGH, LOW }

data class SwingPoint(
    val index: Int,
    val date: java.time.LocalDate,
    val price: java.math.BigDecimal,
    val type: SwingType,
)

data class PatternMatch(
    val patternType: String,
    val confidenceScore: Int,
    val swingPoints: List<SwingPoint>,
    val candleFrom: java.time.LocalDate,
    val candleTo: java.time.LocalDate,
    val description: String,
)

@Service
class PatternRecognizerService(
    private val queryService: PatternRecognizerQueryService,
    private val objectMapper: ObjectMapper,
    private val detectedPatternRepository: DetectedPatternRepository,
) {
    @Cacheable(cacheNames = [CacheConfig.PATTERN], key = "#stockId + ':' + #lookbackDays")
    @Transactional
    fun detectPatterns(stockId: Long, lookbackDays: Int = 90): List<PatternMatch> {
        val matches = queryService.detectPatternsCompute(stockId, lookbackDays)

        matches.filter { it.confidenceScore >= 70 }.forEach { match ->
            detectedPatternRepository.save(
                DetectedPattern(
                    stockId = stockId,
                    patternType = match.patternType,
                    confidenceScore = match.confidenceScore,
                    swingPointsJson = objectMapper.writeValueAsString(match.swingPoints),
                    detectedAt = java.time.Instant.now(),
                    candleFrom = match.candleFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    candleTo = match.candleTo.atStartOfDay(ZoneOffset.UTC).toInstant(),
                )
            )
        }

        return matches
    }
}
