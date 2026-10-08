package com.monticker.api.watchrule.application

import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleOrderType
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.domain.WatchRuleSizeType
import com.monticker.api.watchrule.domain.WatchRuleSizing
import com.monticker.api.watchrule.domain.WatchRuleTargetType
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** ADR-051 — watch rule CRUD. 발동은 [WatchRuleExecutor]가 한다. */
@Service
@Transactional
class WatchRuleService(
    private val ruleRepo: WatchRuleRepository,
    private val execRepo: WatchRuleExecutionRepository,
    private val jdbc: JdbcTemplate,
    private val signalAccess: StrategySignalAccess,
    private val guards: WatchRuleGuards,
    private val targets: WatchRuleTargets,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<WatchRule> = ruleRepo.findAllByUserIdOrderByCreatedAtDesc(userId)

    /** 오늘(KST) 규칙별 체결 수 — 카드의 "오늘 발동 / 한도". 서버가 한도를 집행하는 바로 그 카운터다. */
    @Transactional(readOnly = true)
    fun todayCounts(rules: List<WatchRule>): Map<Long, Int> = guards.todayCounts(rules.map { it.id })

    /** 전략 신호 규칙 카드에 보일 전략 이름. */
    /** ADR-095 — 그룹 규칙 카드의 그룹 이름. 지워진 그룹은 맵에 없다(화면은 "삭제된 그룹"). */
    @Transactional(readOnly = true)
    fun groupNames(userId: Long, rules: List<WatchRule>): Map<Long, String> =
        targets.groupNames(userId, rules.mapNotNull { it.targetGroupId }.distinct())

    @Transactional(readOnly = true)
    fun strategyName(ruleSetId: String?): String? = ruleSetId?.let { runCatching { signalAccess.nameOf(it) }.getOrNull() }

    fun create(
        userId: Long,
        stockId: Long?,
        eventType: String,
        side: String,
        quantity: Int?,
        minImportanceScore: Int,
        cooldownSec: Int,
        name: String? = null,
        ruleSetId: String? = null,
        signalDirection: String? = null,
        requiredEventTypes: List<String> = emptyList(),
        conditionWindowSec: Int? = null,
        dailyLimit: Int? = null,
        targetType: String = WatchRuleTargetType.STOCK.name,
        targetGroupId: Long? = null,
        orderType: String = WatchRuleOrderType.MARKET.name,
        limitOffsetBps: Int? = null,
        sizeType: String = WatchRuleSizeType.SHARES.name,
        equityPct: BigDecimal? = null,
    ): WatchRule {
        // 검증은 여기서 한다 — DB CHECK 제약은 마지막 방어선이고, 사용자에게는 무엇이 틀렸는지 알려야 한다.
        val target = enumOf<WatchRuleTargetType>(targetType, "대상 유형")
        val type = enumOf<WatchRuleOrderType>(orderType, "주문 유형")
        val size = enumOf<WatchRuleSizeType>(sizeType, "수량 기준")
        // ADR-095 — 수량 기준: 주 수 또는 계좌 평가자산 %(1~25)
        when (size) {
            WatchRuleSizeType.SHARES -> {
                require(quantity != null && quantity > 0) { "수량은 1 이상이어야 합니다" }
                require(equityPct == null) { "계좌 비율은 수량 기준이 계좌 %일 때만 씁니다" }
            }
            WatchRuleSizeType.EQUITY_PCT -> {
                require(equityPct != null) { "계좌 비율(%)이 필요합니다" }
                WatchRuleSizing.requireEquityPct(equityPct)
                require(quantity == null) { "계좌 % 규칙에는 수량(주)을 넣지 않습니다" }
            }
        }
        // ADR-095 — 지정가 오프셋(bps, ±1000)
        when (type) {
            WatchRuleOrderType.MARKET -> require(limitOffsetBps == null) { "지정가 오프셋은 지정가 규칙에만 씁니다" }
            WatchRuleOrderType.LIMIT -> {
                require(limitOffsetBps != null) { "지정가 규칙에는 오프셋(bp)이 필요합니다" }
                WatchRuleSizing.requireOffset(limitOffsetBps)
            }
        }
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
        // ADR-095 — 대상: 종목 하나 또는 내 관심종목 그룹. 남의 그룹은 없는 그룹과 같은 404(security-review H6).
        when (target) {
            WatchRuleTargetType.STOCK -> {
                require(stockId != null) { "대상 종목(stockId)이 필요합니다" }
                require(targetGroupId == null) { "종목 규칙에는 그룹을 넣지 않습니다" }
                val stockExists = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM stocks WHERE id = ?)", Boolean::class.java, stockId,
                ) ?: false
                if (!stockExists) throw NoSuchElementException("종목을 찾을 수 없습니다: $stockId")
            }
            WatchRuleTargetType.GROUP -> {
                require(targetGroupId != null) { "대상 관심종목 그룹(targetGroupId)이 필요합니다" }
                require(stockId == null) { "그룹 규칙에는 종목을 넣지 않습니다" }
                if (!targets.ownsGroup(userId, targetGroupId)) throw NoSuchElementException("관심종목 그룹을 찾을 수 없습니다: $targetGroupId")
            }
        }

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
                targetType = target,
                targetGroupId = if (target == WatchRuleTargetType.GROUP) targetGroupId else null,
                orderType = type,
                limitOffsetBps = limitOffsetBps,
                sizeType = size,
                equityPct = equityPct,
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
        limitOffsetBps: Int? = null,
        equityPct: BigDecimal? = null,
    ): WatchRule {
        val rule = owned(userId, ruleId)
        // ADR-095 — 기준(대상·주문 유형·수량 기준)은 바꾸지 않는다. 그 기준에 맞는 값만 고칠 수 있다.
        quantity?.let {
            require(rule.sizeType == WatchRuleSizeType.SHARES) { "계좌 % 규칙의 수량(주)은 바꿀 수 없습니다 — 계좌 비율을 고치세요" }
            require(it > 0) { "수량은 1 이상이어야 합니다" }
        }
        equityPct?.let {
            require(rule.sizeType == WatchRuleSizeType.EQUITY_PCT) { "계좌 비율은 계좌 % 규칙에만 씁니다" }
            WatchRuleSizing.requireEquityPct(it)
        }
        limitOffsetBps?.let {
            require(rule.orderType == WatchRuleOrderType.LIMIT) { "지정가 오프셋은 지정가 규칙에만 씁니다" }
            WatchRuleSizing.requireOffset(it)
        }
        // 그룹이 지워져 꺼진 규칙(V92 트리거)은 다시 켤 수 없다 — 대상이 없다.
        if (isActive == true && rule.isGroupRule) {
            require(targets.ownsGroup(userId, rule.targetGroupId!!)) { "대상 관심종목 그룹이 삭제되어 다시 켤 수 없습니다 — 새 규칙을 만드세요" }
        }
        minImportanceScore?.let { require(it in 0..100) { "중요도 하한은 0~100 사이여야 합니다" } }
        cooldownSec?.let { require(it >= 0) { "쿨다운은 0 이상이어야 합니다" } }
        name?.let { require(it.trim().length <= 100) { "규칙 이름은 100자 이하여야 합니다" } }
        // 0 = 제한 해제
        dailyLimit?.let { require(it == 0 || it in 1..1000) { "하루 최대 발동은 1~1000회(0 = 제한 없음)여야 합니다" } }
        rule.update(quantity, minImportanceScore, cooldownSec, isActive, name, dailyLimit, limitOffsetBps, equityPct)
        return ruleRepo.save(rule)
    }

    fun delete(userId: Long, ruleId: Long) = ruleRepo.delete(owned(userId, ruleId))

    @Transactional(readOnly = true)
    fun executions(userId: Long, limit: Int): List<WatchRuleExecution> =
        execRepo.findAllByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit.coerceIn(1, 200)))

    private fun owned(userId: Long, ruleId: Long): WatchRule {
        // 남의 룰은 없는 룰과 같은 404 — 400/403으로 구분하면 룰 id 존재 여부를 열거할 수 있다
        return ruleRepo.findById(ruleId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw NoSuchElementException("룰을 찾을 수 없습니다: $ruleId")
    }

    private inline fun <reified E : Enum<E>> enumOf(value: String, label: String): E =
        runCatching { enumValueOf<E>(value.uppercase()) }
            .getOrElse { throw IllegalArgumentException("$label 값이 올바르지 않습니다: $value (가능: ${enumValues<E>().joinToString()})") }

    companion object {
        /** worker `DetectedEventType` 과 같아야 한다. 탐지기가 늘면 여기에 추가한다. */
        val SUPPORTED_EVENT_TYPES = setOf("PRICE_SPIKE", "PRICE_DROP", "VOLUME_SURGE")
    }
}
