package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.QuantBacktestResult
import org.springframework.data.jpa.repository.JpaRepository

interface QuantBacktestResultRepository : JpaRepository<QuantBacktestResult, Long> {
    fun findAllByRuleSetId(ruleSetId: String): List<QuantBacktestResult>
}
