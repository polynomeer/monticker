package com.monticker.api.alert.api

import com.monticker.api.alert.application.AlertService
import com.monticker.api.alert.domain.AlertRuleType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/alerts")
class AlertController(private val alertService: AlertService) {

    private val tempUserId = 1L

    @GetMapping("/rules")
    fun getRules(): ResponseEntity<List<AlertRuleResponse>> =
        ResponseEntity.ok(alertService.getRules(tempUserId).map { AlertRuleResponse.from(it) })

    @PostMapping("/rules")
    fun createRule(@RequestBody request: CreateAlertRuleRequest): ResponseEntity<AlertRuleResponse> {
        val ruleType = try {
            AlertRuleType.valueOf(request.ruleType)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        val rule = alertService.createRule(tempUserId, request.stockId, ruleType, request.condition)
        return ResponseEntity.ok(AlertRuleResponse.from(rule))
    }

    @DeleteMapping("/rules/{ruleId}")
    fun deactivateRule(@PathVariable ruleId: Long): ResponseEntity<Void> {
        return try {
            alertService.deactivateRule(tempUserId, ruleId)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
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
