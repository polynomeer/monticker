package com.monticker.api.alert.application

import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class AlertService(
    private val alertRuleRepository: AlertRuleRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun getRules(userId: Long): List<AlertRule> =
        alertRuleRepository.findAllByUserIdAndIsActiveTrue(userId)

    fun createRule(
        userId: Long,
        stockId: Long?,
        ruleType: AlertRuleType,
        condition: Map<String, Any>,
    ): AlertRule {
        val rule = AlertRule(
            userId = userId,
            stockId = stockId,
            ruleType = ruleType,
            conditionJson = objectMapper.writeValueAsString(condition),
        )
        return alertRuleRepository.save(rule)
    }

    fun deactivateRule(userId: Long, ruleId: Long) {
        val rule = alertRuleRepository.findById(ruleId).orElseThrow {
            NoSuchElementException("Alert rule not found: $ruleId")
        }
        require(rule.userId == userId) { "Access denied" }
        rule.isActive = false
        rule.updatedAt = Instant.now()
        alertRuleRepository.save(rule)
    }
}
