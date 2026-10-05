package com.monticker.api.brokerage.api

import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.common.aop.Audited
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

data class ResolveOrderRequest(
    val brokerOrderId: String? = null,
    val notPlaced: Boolean = false,
    @field:NotBlank val note: String,
)

data class AdminOrderResponse(
    val id: Long,
    val userId: Long,
    val accountId: Long,
    val symbol: String,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val status: String,
    val pgOrderId: String?,
    val clientOrderId: String?,
    val needsReview: Boolean,
    val reconcileAttempts: Int,
    val resolvedBy: String?,
    val resolutionNote: String?,
    val rejectReason: String?,
    val submittedAt: Instant,
)

/**
 * ADR-056 Note — 결과 불명 실거래 주문의 운영 화면. 대조 잡이 자동으로 고르지 못한 주문(needs_review)을 사람이 확정한다.
 * 관리자 전용(SecurityConfig의 /api/admin 하위 ADMIN 규칙 + @PreAuthorize), 모든 호출은 @Audited와 주문 행(resolved_by_user·resolution_note)에 남는다.
 */
@RestController
@RequestMapping("/api/admin/brokerage-orders")
@PreAuthorize("hasRole('ADMIN')")
@Audited
class BrokerageOrderAdminController(
    private val brokerageService: BrokerageService,
) {
    @GetMapping("/unresolved")
    fun unresolved(): ResponseEntity<List<AdminOrderResponse>> =
        ResponseEntity.ok(brokerageService.unresolvedOrders().map { it.toResponse() })

    @PostMapping("/{id}/resolve")
    fun resolve(
        @AuthenticationPrincipal adminId: Long?,
        @PathVariable id: Long,
        @Valid @RequestBody req: ResolveOrderRequest,
    ): ResponseEntity<AdminOrderResponse> =
        ResponseEntity.ok(brokerageService.resolveManually(id, adminId, req.brokerOrderId?.trim()?.ifEmpty { null }, req.notPlaced, req.note).toResponse())

    private fun BrokerageOrder.toResponse() = AdminOrderResponse(
        id, userId, accountId, symbol, side.name, orderType.name, quantity, limitPrice, status.name, pgOrderId, clientOrderId,
        needsReview, reconcileAttempts, resolvedBy?.name, resolutionNote, rejectReason, submittedAt,
    )
}
