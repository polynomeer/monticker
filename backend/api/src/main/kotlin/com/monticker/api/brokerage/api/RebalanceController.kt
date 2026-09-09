package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.RebalanceExecutionService
import com.monticker.api.brokerage.application.RebalanceLegPlan
import com.monticker.api.brokerage.application.RebalancePreview
import com.monticker.api.brokerage.application.RebalanceTargetService
import com.monticker.api.brokerage.domain.RebalanceExecution
import com.monticker.api.brokerage.domain.RebalanceExecutionLeg
import com.monticker.api.brokerage.domain.RebalanceTarget
import com.monticker.api.brokerage.domain.RebalanceTargetSource
import com.monticker.api.common.aop.RateLimited
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

// ── 요청/응답 DTO ─────────────────────────────────────────────────────────────

data class SaveRebalanceTargetRequest(
    val weights: Map<String, BigDecimal>,
    val thresholdPct: BigDecimal = BigDecimal("5.00"),
    val source: String = "MANUAL",
)

data class RebalanceTargetResponse(
    val id: Long,
    val weights: Map<String, BigDecimal>,
    val thresholdPct: BigDecimal,
    val source: String,
    val updatedAt: Instant,
)

data class RebalanceLegResponse(
    val symbol: String,
    val side: String,
    val targetWeight: BigDecimal,
    val currentWeight: BigDecimal,
    val diffPct: BigDecimal,
    val quantity: Int,
)

data class RebalancePreviewResponse(
    val totalValue: BigDecimal,
    val legs: List<RebalanceLegResponse>,
)

data class RebalanceExecutionResponse(
    val id: Long,
    val status: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val legs: List<RebalanceExecutionLegResponse>,
)

data class RebalanceExecutionLegResponse(
    val symbol: String,
    val side: String,
    val quantity: Int,
    val status: String,
    val executedOrderId: Long?,
    val failReason: String?,
)

// ── 컨트롤러 ───────────────────────────────────────────────────────────────────

/** ADR-034 — 리밸런싱 실행 자동화(실브로커리지, 수동 실행). */
@RestController
@RequestMapping("/api/rebalance")
class RebalanceController(
    private val targetService: RebalanceTargetService,
    private val executionService: RebalanceExecutionService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @PostMapping("/target")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "rebalance.target")
    fun saveTarget(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: SaveRebalanceTargetRequest,
    ): ResponseEntity<RebalanceTargetResponse> {
        val target = targetService.save(
            userId(token), req.weights, req.thresholdPct, RebalanceTargetSource.valueOf(req.source.uppercase()),
        )
        return ResponseEntity.ok(target.toResponse(targetService.parseWeights(target)))
    }

    @GetMapping("/target")
    fun getTarget(@RequestHeader("Authorization") token: String): ResponseEntity<RebalanceTargetResponse?> {
        val target = targetService.get(userId(token)) ?: return ResponseEntity.ok(null)
        return ResponseEntity.ok(target.toResponse(targetService.parseWeights(target)))
    }

    @GetMapping("/preview")
    fun preview(@RequestHeader("Authorization") token: String): ResponseEntity<RebalancePreviewResponse> =
        ResponseEntity.ok(executionService.preview(userId(token)).toResponse())

    @PostMapping("/execute")
    @RateLimited(limit = 10, windowSec = 60, keyPrefix = "rebalance.execute")
    fun execute(@RequestHeader("Authorization") token: String): ResponseEntity<RebalanceExecutionResponse> {
        val uid = userId(token)
        val execution = executionService.execute(uid)
        return ResponseEntity.ok(execution.toResponse(executionService.getLegs(execution.id)))
    }

    @GetMapping("/executions")
    fun getExecutions(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<RebalanceExecutionResponse>> {
        val page = executionService.getExecutions(userId(token), pageable)
            .map { it.toResponse(executionService.getLegs(it.id)) }
        return ResponseEntity.ok(page)
    }

    private fun RebalanceTarget.toResponse(weights: Map<String, BigDecimal>) = RebalanceTargetResponse(
        id = id, weights = weights, thresholdPct = thresholdPct, source = source.name, updatedAt = updatedAt,
    )

    private fun RebalancePreview.toResponse() = RebalancePreviewResponse(
        totalValue = totalValue,
        legs = legs.map { it.toResponse() },
    )

    private fun RebalanceLegPlan.toResponse() = RebalanceLegResponse(
        symbol = symbol, side = side.name, targetWeight = targetWeight, currentWeight = currentWeight,
        diffPct = diffPct, quantity = quantity,
    )

    private fun RebalanceExecution.toResponse(legs: List<RebalanceExecutionLeg>) = RebalanceExecutionResponse(
        id = id, status = status.name, requestedAt = requestedAt, completedAt = completedAt,
        legs = legs.map { it.toResponse() },
    )

    private fun RebalanceExecutionLeg.toResponse() = RebalanceExecutionLegResponse(
        symbol = symbol, side = side.name, quantity = quantity, status = status.name,
        executedOrderId = executedOrderId, failReason = failReason,
    )
}
