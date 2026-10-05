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
        return ResponseEntity.ok(rules.map { WatchRuleResponse.from(it, today[it.id] ?: 0, it.ruleSetId?.let(names::get)) })
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
                )
        return ResponseEntity.ok(WatchRuleResponse.from(rule, 0, service.strategyName(rule.ruleSetId)))
    }

    @PatchMapping("/{ruleId}")
    fun update(
        @PathVariable ruleId: Long,
        @Valid @RequestBody req: UpdateWatchRuleRequest,
    ): ResponseEntity<WatchRuleResponse> {
        val rule = service.update(userId(), ruleId, req.quantity, req.minImportanceScore, req.cooldownSec, req.isActive, req.name, req.dailyLimit)
        return ResponseEntity.ok(WatchRuleResponse.from(rule, service.todayCounts(listOf(rule))[rule.id] ?: 0, service.strategyName(rule.ruleSetId)))
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
    @field:Positive val stockId: Long,
    @field:NotBlank val eventType: String,
    @field:NotBlank val side: String,
    @field:Positive val quantity: Int,
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
)

data class WatchRuleResponse(
    val id: Long,
    val stockId: Long,
    val eventType: String,
    val side: String,
    val quantity: Int,
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
) {
    companion object {
        fun from(r: WatchRule, todayExecutions: Int = 0, ruleSetName: String? = null) = WatchRuleResponse(
            id = r.id, stockId = r.stockId, eventType = r.eventType, side = r.side.name,
            quantity = r.quantity, minImportanceScore = r.minImportanceScore,
            cooldownSec = r.cooldownSec, isActive = r.isActive, createdAt = r.createdAt,
            name = r.name, ruleSetId = r.ruleSetId, ruleSetName = ruleSetName, signalDirection = r.signalDirection,
            requiredEventTypes = r.requiredTypes(), conditionWindowSec = r.conditionWindowSec,
            dailyLimit = r.dailyLimit, todayExecutions = todayExecutions,
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
) {
    companion object {
        fun from(e: WatchRuleExecution) = WatchRuleExecutionResponse(
            id = e.id, watchRuleId = e.watchRuleId, stockEventId = e.stockEventId, quantSignalId = e.quantSignalId, status = e.status.name,
            orderId = e.orderId, fillPrice = e.fillPrice, quantity = e.quantity, reason = e.reason,
            createdAt = e.createdAt,
        )
    }
}
