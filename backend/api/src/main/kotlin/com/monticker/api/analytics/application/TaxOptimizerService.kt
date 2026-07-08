package com.monticker.api.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.analytics.domain.TaxHarvestingLog
import com.monticker.api.analytics.infrastructure.TaxHarvestingLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TaxOptimizerService(
    private val queryService: TaxHarvestingQueryService,
    private val objectMapper: ObjectMapper,
    private val taxHarvestingLogRepository: TaxHarvestingLogRepository,
) {
    @Transactional
    fun findHarvestingCandidates(userId: Long): TaxHarvestingResponse {
        val response = queryService.findCandidates(userId)

        taxHarvestingLogRepository.save(
            TaxHarvestingLog(
                userId = userId,
                realizedGainYtd = response.realizedGainYtd,
                candidatesJson = objectMapper.writeValueAsString(response.candidates),
                estimatedTaxSaving = response.totalEstimatedTaxSaving,
                taxRateAssumed = response.taxRateAssumed,
            )
        )

        return response
    }
}
