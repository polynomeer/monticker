package com.monticker.api.watchrule.application

import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** ADR-051 — watch rule CRUD. 발동은 [WatchRuleExecutor]가 한다. */
@Service
@Transactional
class WatchRuleService(
    private val ruleRepo: WatchRuleRepository,
    private val execRepo: WatchRuleExecutionRepository,
    private val jdbc: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<WatchRule> = ruleRepo.findAllByUserIdOrderByCreatedAtDesc(userId)

    fun create(
        userId: Long,
        stockId: Long,
        eventType: String,
        side: String,
        quantity: Int,
        minImportanceScore: Int,
        cooldownSec: Int,
    ): WatchRule {
        // 검증은 여기서 한다 — DB CHECK 제약은 마지막 방어선이고, 사용자에게는 무엇이 틀렸는지 알려야 한다.
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        require(minImportanceScore in 0..100) { "중요도 하한은 0~100 사이여야 합니다" }
        require(cooldownSec >= 0) { "쿨다운은 0 이상이어야 합니다" }
        require(eventType in SUPPORTED_EVENT_TYPES) {
            "지원하지 않는 이벤트 유형입니다: $eventType (가능: ${SUPPORTED_EVENT_TYPES.joinToString()})"
        }
        val sideEnum = runCatching { WatchRuleSide.valueOf(side.uppercase()) }
            .getOrElse { throw IllegalArgumentException("매수/매도 구분이 올바르지 않습니다: $side") }
        val stockExists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM stocks WHERE id = ?)", Boolean::class.java, stockId,
        ) ?: false
        if (!stockExists) throw NoSuchElementException("종목을 찾을 수 없습니다: $stockId")

        return ruleRepo.save(
            WatchRule(
                userId = userId,
                stockId = stockId,
                eventType = eventType,
                side = sideEnum,
                quantity = quantity,
                minImportanceScore = minImportanceScore,
                cooldownSec = cooldownSec,
            )
        )
    }

    fun update(
        userId: Long,
        ruleId: Long,
        quantity: Int?,
        minImportanceScore: Int?,
        cooldownSec: Int?,
        isActive: Boolean?,
    ): WatchRule {
        val rule = owned(userId, ruleId)
        quantity?.let { require(it > 0) { "수량은 1 이상이어야 합니다" } }
        minImportanceScore?.let { require(it in 0..100) { "중요도 하한은 0~100 사이여야 합니다" } }
        cooldownSec?.let { require(it >= 0) { "쿨다운은 0 이상이어야 합니다" } }
        rule.update(quantity, minImportanceScore, cooldownSec, isActive)
        return ruleRepo.save(rule)
    }

    fun delete(userId: Long, ruleId: Long) = ruleRepo.delete(owned(userId, ruleId))

    @Transactional(readOnly = true)
    fun executions(userId: Long, limit: Int): List<WatchRuleExecution> =
        execRepo.findAllByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit.coerceIn(1, 200)))

    private fun owned(userId: Long, ruleId: Long): WatchRule {
        val rule = ruleRepo.findById(ruleId).orElseThrow { NoSuchElementException("룰을 찾을 수 없습니다: $ruleId") }
        require(rule.userId == userId) { "본인의 룰만 수정할 수 있습니다" }
        return rule
    }

    companion object {
        /** worker `DetectedEventType` 과 같아야 한다. 탐지기가 늘면 여기에 추가한다. */
        val SUPPORTED_EVENT_TYPES = setOf("PRICE_SPIKE", "PRICE_DROP", "VOLUME_SURGE")
    }
}
