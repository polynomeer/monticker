package com.monticker.api.alert.api

import com.monticker.api.alert.application.AlertHistoryResult
import com.monticker.api.alert.application.AlertService
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.common.aop.RateLimited
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/alerts")
class AlertController(private val alertService: AlertService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/rules")
    fun getRules(): ResponseEntity<List<AlertRuleResponse>> =
        ResponseEntity.ok(alertService.getRules(userId()).map { AlertRuleResponse.from(it) })

    @PostMapping("/rules")
    @RateLimited(limit = 20, windowSec = 3600, keyPrefix = "alert.create")
    fun createRule(@RequestBody request: CreateAlertRuleRequest): ResponseEntity<AlertRuleResponse> {
        val ruleType = try {
            AlertRuleType.valueOf(request.ruleType)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        val rule = alertService.createRule(userId(), request.stockId, ruleType, request.condition)
        return ResponseEntity.ok(AlertRuleResponse.from(rule))
    }

    @DeleteMapping("/rules/{ruleId}")
    fun deactivateRule(@PathVariable ruleId: Long): ResponseEntity<Void> {
        return try {
            alertService.deactivateRule(userId(), ruleId)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/stats")
    fun getStats() =
        ResponseEntity.ok(alertService.getStats(userId()))

    /**
     * 내 알림 이력 검색.
     *
     * GET /api/alerts/history/search?query=목표가초과
     * GET /api/alerts/history/search?stockId=1&ruleType=PRICE_ABOVE&deliveryStatus=SENT
     * GET /api/alerts/history/search?from=2026-01-01T00:00:00Z&to=2026-07-01T00:00:00Z
     */
    @GetMapping("/history/search")
    fun searchHistory(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) stockId: Long?,
        @RequestParam(required = false) ruleType: String?,
        @RequestParam(required = false) deliveryStatus: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<AlertHistoryResponse>> {
        val results = alertService.searchHistory(
            userId         = userId(),
            query          = query,
            stockId        = stockId,
            ruleType       = ruleType,
            deliveryStatus = deliveryStatus,
            from           = from,
            to             = to,
            limit          = limit,
        )
        return ResponseEntity.ok(results.map { AlertHistoryResponse.from(it) })
    }
}

data class AlertHistoryResponse(
    val id: Long,
    val ruleId: Long,
    val stockId: Long?,
    val ruleType: String,
    val message: String,
    val deliveryStatus: String,
    val triggeredAt: Instant,
    val score: Float?,
) {
    companion object {
        fun from(r: AlertHistoryResult) = AlertHistoryResponse(
            id             = r.id,
            ruleId         = r.ruleId,
            stockId        = r.stockId,
            ruleType       = r.ruleType,
            message        = r.message,
            deliveryStatus = r.deliveryStatus,
            triggeredAt    = r.triggeredAt,
            score          = r.score,
        )
    }
}

data class CreateAlertRuleRequest(
    val stockId: Long? = null,
    val ruleType: String,
    val condition: Map<String, Any>,
)

data class AlertRuleResponse(
    val id: Long,
    val stockId: Long?,
    val ruleType: String,
    val conditionJson: String,
    val isActive: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(rule: com.monticker.api.alert.domain.AlertRule) = AlertRuleResponse(
            id = rule.id,
            stockId = rule.stockId,
            ruleType = rule.ruleType.name,
            conditionJson = rule.conditionJson,
            isActive = rule.isActive,
            createdAt = rule.createdAt,
        )
    }
}
