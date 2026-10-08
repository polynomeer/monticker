package com.monticker.api.watchrule.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.watchrule.application.WatchRuleService
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-051 — 이벤트 트리거 모의 자동주문 룰.
 *
 * 모의투자 계좌에만 적용된다. 실브로커 계좌의 자동 실행 엔드포인트는 의도적으로 없다(ADR-025/036).
 */
@Validated
@RestController
@RequestMapping("/api/watch-rules")
class WatchRuleController(private val service: WatchRuleService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping
    fun list(): ResponseEntity<List<WatchRuleResponse>> {
        val rules = service.list(userId())
        val today = service.todayCounts(rules)
        val names = rules.mapNotNull { it.ruleSetId }.distinct().associateWith { service.strategyName(it) }
        val groups = service.groupNames(userId(), rules)
        return ResponseEntity.ok(rules.map { WatchRuleResponse.from(it, today[it.id] ?: 0, it.ruleSetId?.let(names::get), groups) })
    }

    @PostMapping
    @RateLimited(limit = 20, windowSec = 3600, keyPrefix = "watchrule.create")
    fun create(@Valid @RequestBody req: CreateWatchRuleRequest): ResponseEntity<WatchRuleResponse> {
        val rule = service.create(
                    userId = userId(),
                    stockId = req.stockId,
                    eventType = req.eventType,
                    side = req.side,
                    quantity = req.quantity,
                    minImportanceScore = req.minImportanceScore ?: 0,
                    cooldownSec = req.cooldownSec ?: DEFAULT_COOLDOWN_SEC,
                    name = req.name,
                    ruleSetId = req.ruleSetId,
                    signalDirection = req.signalDirection,
                    requiredEventTypes = req.requiredEventTypes ?: emptyList(),
                    conditionWindowSec = req.conditionWindowSec,
                    dailyLimit = req.dailyLimit,
                    targetType = req.targetType ?: "STOCK",
                    targetGroupId = req.targetGroupId,
                    orderType = req.orderType ?: "MARKET",
                    limitOffsetBps = req.limitOffsetBps,
                    sizeType = req.sizeType ?: "SHARES",
                    equityPct = req.equityPct,
                )
        return ResponseEntity.ok(WatchRuleResponse.from(rule, 0, service.strategyName(rule.ruleSetId), service.groupNames(userId(), listOf(rule))))
    }

    @PatchMapping("/{ruleId}")
    fun update(
        @PathVariable ruleId: Long,
        @Valid @RequestBody req: UpdateWatchRuleRequest,
    ): ResponseEntity<WatchRuleResponse> {
        val rule = service.update(
            userId(), ruleId, req.quantity, req.minImportanceScore, req.cooldownSec, req.isActive, req.name, req.dailyLimit,
            req.limitOffsetBps, req.equityPct,
            targetType = req.targetType, stockId = req.stockId, targetGroupId = req.targetGroupId,
            orderType = req.orderType, sizeType = req.sizeType,
        )
        return ResponseEntity.ok(WatchRuleResponse.from(
            rule, service.todayCounts(listOf(rule))[rule.id] ?: 0, service.strategyName(rule.ruleSetId), service.groupNames(userId(), listOf(rule)),
        ))
    }

    @DeleteMapping("/{ruleId}")
    fun delete(@PathVariable ruleId: Long): ResponseEntity<Void> {
        service.delete(userId(), ruleId)
        return ResponseEntity.noContent().build()
    }

    /** 발동 이력 — 체결뿐 아니라 거부·건너뜀도 이유와 함께 보여준다. */
    @GetMapping("/executions")
    fun executions(@RequestParam(defaultValue = "50") limit: Int): ResponseEntity<List<WatchRuleExecutionResponse>> =
        ResponseEntity.ok(service.executions(userId(), limit).map { WatchRuleExecutionResponse.from(it) })

    companion object {
        const val DEFAULT_COOLDOWN_SEC = 600
    }
}

data class CreateWatchRuleRequest(
    /** 대상이 종목(STOCK)일 때 필수. 그룹 규칙이면 비운다. */
    @field:Positive val stockId: Long? = null,
    @field:NotBlank val eventType: String,
    @field:NotBlank val side: String,
    /** 수량 기준이 주 수(SHARES)일 때 필수. 계좌 %(EQUITY_PCT)면 비운다. */
    @field:Positive val quantity: Int? = null,
    val minImportanceScore: Int? = null,
    val cooldownSec: Int? = null,
    /** ADR-077 */
    val name: String? = null,
    /** eventType = QUANT_SIGNAL일 때 전략(룰셋) id와 신호 방향(BUY·SELL) */
    val ruleSetId: String? = null,
    val signalDirection: String? = null,
    /** 복합 조건 — 주 이벤트 앞 conditionWindowSec 안에 함께 감지됐어야 하는 유형 */
    val requiredEventTypes: List<String>? = null,
    val conditionWindowSec: Int? = null,
    /** 하루(KST) 최대 체결 횟수. 없으면 제한 없음 */
    val dailyLimit: Int? = null,
    /** ADR-095 — STOCK(기본) | GROUP. GROUP이면 targetGroupId(내 관심종목 그룹) 필수 */
    val targetType: String? = null,
    val targetGroupId: Long? = null,
    /** ADR-095 — MARKET(기본) | LIMIT. LIMIT이면 발동 가격 대비 오프셋 limitOffsetBps(±1000) 필수 */
    val orderType: String? = null,
    val limitOffsetBps: Int? = null,
    /** ADR-095 — SHARES(기본) | EQUITY_PCT. EQUITY_PCT면 equityPct(1~25) 필수 */
    val sizeType: String? = null,
    val equityPct: BigDecimal? = null,
)

data class UpdateWatchRuleRequest(
    val quantity: Int? = null,
    val minImportanceScore: Int? = null,
    val cooldownSec: Int? = null,
    val isActive: Boolean? = null,
    /** 빈 문자열이면 이름을 지운다 */
    val name: String? = null,
    /** 0이면 제한 해제 */
    val dailyLimit: Int? = null,
    /** ADR-095 — 지정가 규칙의 오프셋(bp) */
    val limitOffsetBps: Int? = null,
    /** ADR-095 — 계좌 % 규칙의 비율 */
    val equityPct: BigDecimal? = null,
    /**
     * ADR-098 — 기준 바꾸기. STOCK | GROUP. 바꾸면 새 대상의 값(stockId 또는 targetGroupId)이 함께 와야 한다.
     * 기준을 그대로 두고 stockId·targetGroupId만 보내면 같은 유형 안에서 대상을 바꾼다.
     */
    val targetType: String? = null,
    @field:Positive val stockId: Long? = null,
    @field:Positive val targetGroupId: Long? = null,
    /** ADR-098 — MARKET | LIMIT. LIMIT으로 바꾸면 limitOffsetBps가 함께 와야 한다. MARKET으로 바꾸면 오프셋은 지워진다. */
    val orderType: String? = null,
    /** ADR-098 — SHARES | EQUITY_PCT. 바꾸면 새 기준의 값(quantity 또는 equityPct)이 함께 와야 한다. */
    val sizeType: String? = null,
)

data class WatchRuleResponse(
    val id: Long,
    /** 그룹 규칙이면 null */
    val stockId: Long?,
    val eventType: String,
    val side: String,
    /** 계좌 % 규칙이면 null */
    val quantity: Int?,
    val minImportanceScore: Int,
    val cooldownSec: Int,
    val isActive: Boolean,
    val createdAt: Instant,
    val name: String? = null,
    val ruleSetId: String? = null,
    val ruleSetName: String? = null,
    val signalDirection: String? = null,
    val requiredEventTypes: List<String> = emptyList(),
    val conditionWindowSec: Int? = null,
    val dailyLimit: Int? = null,
    /** 오늘(KST) 체결 수 — 서버가 한도를 집행하는 카운터 */
    val todayExecutions: Int = 0,
    /** ADR-095 */
    val targetType: String = "STOCK",
    val targetGroupId: Long? = null,
    /** 그룹 이름. 그룹이 지워졌으면 null이고 [targetGroupMissing]이 true다. */
    val targetGroupName: String? = null,
    val targetGroupMissing: Boolean = false,
    val orderType: String = "MARKET",
    val limitOffsetBps: Int? = null,
    val sizeType: String = "SHARES",
    val equityPct: BigDecimal? = null,
) {
    companion object {
        fun from(r: WatchRule, todayExecutions: Int = 0, ruleSetName: String? = null, groupNames: Map<Long, String> = emptyMap()) = WatchRuleResponse(
            id = r.id, stockId = r.stockId, eventType = r.eventType, side = r.side.name,
            quantity = r.quantity, minImportanceScore = r.minImportanceScore,
            cooldownSec = r.cooldownSec, isActive = r.isActive, createdAt = r.createdAt,
            name = r.name, ruleSetId = r.ruleSetId, ruleSetName = ruleSetName, signalDirection = r.signalDirection,
            requiredEventTypes = r.requiredTypes(), conditionWindowSec = r.conditionWindowSec,
            dailyLimit = r.dailyLimit, todayExecutions = todayExecutions,
            targetType = r.targetType.name, targetGroupId = r.targetGroupId,
            targetGroupName = r.targetGroupId?.let(groupNames::get),
            targetGroupMissing = r.targetGroupId != null && r.targetGroupId !in groupNames,
            orderType = r.orderType.name, limitOffsetBps = r.limitOffsetBps,
            sizeType = r.sizeType.name, equityPct = r.equityPct,
        )
    }
}

data class WatchRuleExecutionResponse(
    val id: Long,
    val watchRuleId: Long,
    val stockEventId: Long?,
    val quantSignalId: Long?,
    val status: String,
    val orderId: Long?,
    val fillPrice: BigDecimal?,
    val quantity: Int?,
    val reason: String?,
    val createdAt: Instant,
    /** ADR-095 — 발동 종목(그룹 규칙은 발동마다 다르다) */
    val stockId: Long? = null,
    /** ADR-095 — 지정가 발동의 지정가 */
    val limitPrice: BigDecimal? = null,
    /** ADR-098 — PLACED 이후 결과(FILLED·CANCELLED)가 정해진 시각. FILLED면 [fillPrice]가 체결가다. */
    val resolvedAt: Instant? = null,
) {
    companion object {
        fun from(e: WatchRuleExecution) = WatchRuleExecutionResponse(
            id = e.id, watchRuleId = e.watchRuleId, stockEventId = e.stockEventId, quantSignalId = e.quantSignalId, status = e.status.name,
            orderId = e.orderId, fillPrice = e.fillPrice, quantity = e.quantity, reason = e.reason,
            createdAt = e.createdAt, stockId = e.stockId, limitPrice = e.limitPrice, resolvedAt = e.resolvedAt,
        )
    }
}
