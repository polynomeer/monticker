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
    fun list(): ResponseEntity<List<WatchRuleResponse>> =
        ResponseEntity.ok(service.list(userId()).map { WatchRuleResponse.from(it) })

    @PostMapping
    @RateLimited(limit = 20, windowSec = 3600, keyPrefix = "watchrule.create")
    fun create(@Valid @RequestBody req: CreateWatchRuleRequest): ResponseEntity<WatchRuleResponse> =
        ResponseEntity.ok(
            WatchRuleResponse.from(
                service.create(
                    userId = userId(),
                    stockId = req.stockId,
                    eventType = req.eventType,
                    side = req.side,
                    quantity = req.quantity,
                    minImportanceScore = req.minImportanceScore ?: 0,
                    cooldownSec = req.cooldownSec ?: DEFAULT_COOLDOWN_SEC,
                )
            )
        )

    @PatchMapping("/{ruleId}")
    fun update(
        @PathVariable ruleId: Long,
        @Valid @RequestBody req: UpdateWatchRuleRequest,
    ): ResponseEntity<WatchRuleResponse> =
        ResponseEntity.ok(
            WatchRuleResponse.from(
                service.update(userId(), ruleId, req.quantity, req.minImportanceScore, req.cooldownSec, req.isActive)
            )
        )

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
)

data class UpdateWatchRuleRequest(
    val quantity: Int? = null,
    val minImportanceScore: Int? = null,
    val cooldownSec: Int? = null,
    val isActive: Boolean? = null,
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
) {
    companion object {
        fun from(r: WatchRule) = WatchRuleResponse(
            id = r.id, stockId = r.stockId, eventType = r.eventType, side = r.side.name,
            quantity = r.quantity, minImportanceScore = r.minImportanceScore,
            cooldownSec = r.cooldownSec, isActive = r.isActive, createdAt = r.createdAt,
        )
    }
}

data class WatchRuleExecutionResponse(
    val id: Long,
    val watchRuleId: Long,
    val stockEventId: Long,
    val status: String,
    val orderId: Long?,
    val fillPrice: BigDecimal?,
    val quantity: Int?,
    val reason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(e: WatchRuleExecution) = WatchRuleExecutionResponse(
            id = e.id, watchRuleId = e.watchRuleId, stockEventId = e.stockEventId, status = e.status.name,
            orderId = e.orderId, fillPrice = e.fillPrice, quantity = e.quantity, reason = e.reason,
            createdAt = e.createdAt,
        )
    }
}
