package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.ForwardTestStatus
import com.monticker.api.quant.domain.QuantForwardTest
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface QuantForwardTestRepository : JpaRepository<QuantForwardTest, Long> {
    fun findByRuleSetIdAndStatus(ruleSetId: String, status: ForwardTestStatus): Optional<QuantForwardTest>
    fun findAllByRuleSetIdOrderByStartedAtDesc(ruleSetId: String): List<QuantForwardTest>
    fun findAllByStatus(status: ForwardTestStatus): List<QuantForwardTest>
}
