package com.monticker.api.matching.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.domain.RiskLimit
import com.monticker.api.matching.infrastructure.RiskLimitRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class RuleResult(
    val rule: String,
    val passed: Boolean,
    val detail: String,
    val current: Double,
    val limit: Double,
)

data class RiskCheckResult(
    val approved: Boolean,
    val blockedBy: String?,
    val severity: String,
    val checks: List<RuleResult>,
)

@Service
@Transactional
class RiskCheckerService(
    private val riskLimitRepo: RiskLimitRepository,
    private val riskRuleQueryService: RiskRuleQueryService,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun check(
        userId: Long,
        stockId: Long,
        side: String,
        qty: Int,
        estimatedPrice: BigDecimal,
    ): RiskCheckResult {
        val limits = riskLimitRepo.findByUserId(userId).orElseGet { RiskLimit(userId = userId) }
        val checks = riskRuleQueryService.evaluate(userId, stockId, side, qty, estimatedPrice, limits)

        val blockedBy = checks.firstOrNull { !it.passed }?.rule
        val approved  = blockedBy == null
        val severity  = when {
            !approved              -> "BLOCKED"
            checks.any { !it.passed } -> "WARNING"
            else                   -> "APPROVED"
        }

        jdbc.update(
            """INSERT INTO risk_check_logs (user_id, stock_id, side, quantity, approved, blocked_by, checks_json)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
            userId, stockId, side, qty, approved, blockedBy,
            objectMapper.writeValueAsString(checks),
        )

        return RiskCheckResult(approved = approved, blockedBy = blockedBy, severity = severity, checks = checks)
    }
}
