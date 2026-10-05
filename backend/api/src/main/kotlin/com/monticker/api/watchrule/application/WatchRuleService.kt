package com.monticker.api.watchrule.application

import com.monticker.api.quant.application.StrategySignalAccess
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
    private val signalAccess: StrategySignalAccess,
    private val guards: WatchRuleGuards,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<WatchRule> = ruleRepo.findAllByUserIdOrderByCreatedAtDesc(userId)

    /** 오늘(KST) 규칙별 체결 수 — 카드의 "오늘 발동 / 한도". 서버가 한도를 집행하는 바로 그 카운터다. */
    @Transactional(readOnly = true)
    fun todayCounts(rules: List<WatchRule>): Map<Long, Int> = guards.todayCounts(rules.map { it.id })

    /** 전략 신호 규칙 카드에 보일 전략 이름. */
    @Transactional(readOnly = true)
    fun strategyName(ruleSetId: String?): String? = ruleSetId?.let { runCatching { signalAccess.nameOf(it) }.getOrNull() }

    fun create(
        userId: Long,
        stockId: Long,
        eventType: String,
        side: String,
        quantity: Int,
        minImportanceScore: Int,
        cooldownSec: Int,
        name: String? = null,
        ruleSetId: String? = null,
        signalDirection: String? = null,
        requiredEventTypes: List<String> = emptyList(),
        conditionWindowSec: Int? = null,
        dailyLimit: Int? = null,
    ): WatchRule {
        // 검증은 여기서 한다 — DB CHECK 제약은 마지막 방어선이고, 사용자에게는 무엇이 틀렸는지 알려야 한다.
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        require(minImportanceScore in 0..100) { "중요도 하한은 0~100 사이여야 합니다" }
        require(cooldownSec >= 0) { "쿨다운은 0 이상이어야 합니다" }
        require(eventType in SUPPORTED_EVENT_TYPES || eventType == WatchRule.QUANT_SIGNAL) {
            "지원하지 않는 이벤트 유형입니다: $eventType (가능: ${(SUPPORTED_EVENT_TYPES + WatchRule.QUANT_SIGNAL).joinToString()})"
        }
        val cleanName = name?.trim()?.ifBlank { null }
        cleanName?.let { require(it.length <= 100) { "규칙 이름은 100자 이하여야 합니다" } }
        dailyLimit?.let { require(it in 1..1000) { "하루 최대 발동은 1~1000회여야 합니다" } }

        // ADR-077 — 전략 신호 규칙: 내 전략이거나 구독한 전략이어야 한다(ADR-035와 같은 기준).
        var direction: String? = null
        if (eventType == WatchRule.QUANT_SIGNAL) {
            require(!ruleSetId.isNullOrBlank()) { "전략 신호 규칙에는 전략(ruleSetId)이 필요합니다" }
            direction = signalDirection?.uppercase()
            require(direction == "BUY" || direction == "SELL") { "신호 방향은 BUY 또는 SELL이어야 합니다" }
            require(signalAccess.canAccess(userId, ruleSetId)) { "내 전략이거나 구독한 전략의 신호만 쓸 수 있습니다" }
            require(requiredEventTypes.isEmpty()) { "복합 조건은 이벤트 규칙에만 쓸 수 있습니다" }
        } else {
            require(ruleSetId == null && signalDirection == null) { "전략·신호 방향은 전략 신호 규칙에만 씁니다" }
        }

        // ADR-077 — 복합 조건: 동반 이벤트 유형(주 이벤트와 다른 지원 유형), 창 1분~24시간.
        val required = requiredEventTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        required.forEach {
            require(it in SUPPORTED_EVENT_TYPES) { "복합 조건에 쓸 수 없는 이벤트 유형입니다: $it" }
            require(it != eventType) { "복합 조건에 주 이벤트와 같은 유형을 넣을 수 없습니다" }
        }
        val window = if (required.isEmpty()) null else (conditionWindowSec ?: WatchRuleExecutor.DEFAULT_WINDOW_SEC)
        window?.let { require(it in 60..86400) { "복합 조건 창은 1분~24시간이어야 합니다" } }
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
                name = cleanName,
                ruleSetId = if (eventType == WatchRule.QUANT_SIGNAL) ruleSetId else null,
                signalDirection = direction,
                requiredEventTypes = required.takeIf { it.isNotEmpty() }?.joinToString(","),
                conditionWindowSec = window,
                dailyLimit = dailyLimit,
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
        name: String? = null,
        dailyLimit: Int? = null,
    ): WatchRule {
        val rule = owned(userId, ruleId)
        quantity?.let { require(it > 0) { "수량은 1 이상이어야 합니다" } }
        minImportanceScore?.let { require(it in 0..100) { "중요도 하한은 0~100 사이여야 합니다" } }
        cooldownSec?.let { require(it >= 0) { "쿨다운은 0 이상이어야 합니다" } }
        name?.let { require(it.trim().length <= 100) { "규칙 이름은 100자 이하여야 합니다" } }
        // 0 = 제한 해제
        dailyLimit?.let { require(it == 0 || it in 1..1000) { "하루 최대 발동은 1~1000회(0 = 제한 없음)여야 합니다" } }
        rule.update(quantity, minImportanceScore, cooldownSec, isActive, name, dailyLimit)
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
