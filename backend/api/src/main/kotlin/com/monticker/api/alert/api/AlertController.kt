package com.monticker.api.alert.api

import com.monticker.api.alert.application.AlertHistoryResult
import com.monticker.api.alert.application.AlertService
import com.monticker.api.alert.domain.AlertRuleConditions
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.common.aop.RateLimited
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Validated
@RestController
@RequestMapping("/api/alerts")
class AlertController(private val alertService: AlertService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    /** 기본은 켜진 규칙만. includePaused=true면 꺼 둔 규칙도(삭제한 규칙은 제외) — 알림 화면의 켜기/끄기 토글용 */
    @GetMapping("/rules")
    fun getRules(@RequestParam(defaultValue = "false") includePaused: Boolean): ResponseEntity<List<AlertRuleResponse>> =
        ResponseEntity.ok(alertService.getRules(userId(), includePaused).map { AlertRuleResponse.from(it) })

    /**
     * ADR-073 — 규칙 켜기/끄기. PATCH /api/alerts/rules/{id}  {"isActive": false}
     * 삭제한 규칙·남의 규칙은 404, 다시 켤 수 없는 규칙(평가기 없는 유형)은 409.
     */
    @PatchMapping("/rules/{ruleId}")
    @RateLimited(limit = 120, windowSec = 3600, keyPrefix = "alert.toggle")
    fun setRuleActive(@PathVariable ruleId: Long, @Valid @RequestBody request: SetAlertRuleActiveRequest): ResponseEntity<AlertRuleResponse> =
        ResponseEntity.ok(AlertRuleResponse.from(alertService.setActive(userId(), ruleId, request.isActive!!)))

    /** ADR-073 — 이력 한 건 읽음(멱등). POST /api/alerts/history/{id}/read */
    @PostMapping("/history/{historyId}/read")
    fun markRead(@PathVariable historyId: Long): ResponseEntity<Void> {
        alertService.markRead(userId(), historyId)
        return ResponseEntity.noContent().build()
    }

    /**
     * ADR-073 — 모두 읽음. POST /api/alerts/history/read-all?upTo=2026-10-06T01:00:00Z
     * upTo(기본: 지금) 이전에 발동한 알림만 — 화면을 연 뒤 새로 온 알림은 남긴다.
     */
    @PostMapping("/history/read-all")
    fun markAllRead(@RequestParam(required = false) upTo: Instant?): ResponseEntity<Map<String, Int>> {
        val now = Instant.now()
        val bound = if (upTo == null || upTo.isAfter(now)) now else upTo
        return ResponseEntity.ok(mapOf("updated" to alertService.markAllRead(userId(), bound)))
    }

    @PostMapping("/rules")
    @RateLimited(limit = 20, windowSec = 3600, keyPrefix = "alert.create")
    fun createRule(@Valid @RequestBody request: CreateAlertRuleRequest): ResponseEntity<AlertRuleResponse> {
        val ruleType = try {
            AlertRuleType.valueOf(request.ruleType)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        if (!isConditionValid(ruleType, request.stockId, request.condition)) return ResponseEntity.badRequest().build()
        val rule = alertService.createRule(userId(), request.stockId, ruleType, request.condition)
        return ResponseEntity.ok(AlertRuleResponse.from(rule))
    }

    private fun isConditionValid(type: AlertRuleType, stockId: Long?, condition: Map<String, Any>): Boolean =
        AlertRuleConditions.isValid(type, stockId, condition)

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
    /** ADR-090 — 규칙 없이 생긴 이력(ruleType = QUANT_SIGNAL)은 null */
    val ruleId: Long?,
    val stockId: Long?,
    val ruleType: String,
    val message: String,
    val deliveryStatus: String,
    val triggeredAt: Instant,
    val score: Float?,
    /** null이면 읽지 않음 */
    val readAt: Instant?,
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
            readAt         = r.readAt,
        )
    }
}

data class SetAlertRuleActiveRequest(
    @field:NotNull @param:JsonProperty("isActive") @get:JsonProperty("isActive") val isActive: Boolean? = null,
)

data class CreateAlertRuleRequest(
    val stockId: Long? = null,
    @field:NotBlank val ruleType: String,
    @field:NotNull val condition: Map<String, Any>,
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
