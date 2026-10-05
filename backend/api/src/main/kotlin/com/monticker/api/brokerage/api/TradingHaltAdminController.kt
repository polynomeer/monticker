package com.monticker.api.brokerage.api

import com.monticker.api.brokerage.application.HaltScope
import com.monticker.api.brokerage.application.TradingHalt
import com.monticker.api.brokerage.application.TradingHaltService
import com.monticker.api.common.aop.Audited
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class HaltRequest(
    val scope: String,
    val target: String? = null,
    @field:NotBlank val reason: String,
)

data class LiftRequest(@field:NotBlank val reason: String)

data class TradingHaltResponse(
    val id: Long,
    val scope: String,
    val target: String?,
    val reason: String,
    val haltedBy: Long?,
    val haltedAt: Instant,
    val liftedBy: Long?,
    val liftedAt: Instant?,
    val liftReason: String?,
    val active: Boolean,
)

/**
 * ADR-057 — 실거래 주문 킬 스위치 관리. 관리자 전용, 모든 호출은 @Audited 구조화 로그 + trading_halts 행으로 남는다.
 * 앱이 죽었을 때의 비상 경로는 SQL 직접 INSERT다(docs/runbooks/trading-halt.md).
 */
@RestController
@RequestMapping("/api/admin/trading-halts")
@PreAuthorize("hasRole('ADMIN')")
@Audited
class TradingHaltAdminController(
    private val tradingHaltService: TradingHaltService,
) {
    @PostMapping
    fun halt(@AuthenticationPrincipal adminId: Long?, @Valid @RequestBody req: HaltRequest): ResponseEntity<TradingHaltResponse> {
        val scope = runCatching { HaltScope.valueOf(req.scope.uppercase()) }
            .getOrElse { throw IllegalArgumentException("scope는 GLOBAL, PROVIDER, USER 중 하나입니다: ${req.scope}") }
        return ResponseEntity.status(HttpStatus.CREATED).body(tradingHaltService.halt(scope, req.target, req.reason, adminId).toResponse())
    }

    @PostMapping("/{id}/lift")
    fun lift(@AuthenticationPrincipal adminId: Long?, @PathVariable id: Long, @Valid @RequestBody req: LiftRequest): ResponseEntity<TradingHaltResponse> =
        ResponseEntity.ok(tradingHaltService.lift(id, req.reason, adminId).toResponse())

    @GetMapping
    fun list(@RequestParam(defaultValue = "true") active: Boolean): ResponseEntity<List<TradingHaltResponse>> =
        ResponseEntity.ok(tradingHaltService.list(active).map { it.toResponse() })

    private fun TradingHalt.toResponse() = TradingHaltResponse(
        id, scope.name, target, reason, haltedBy, haltedAt, liftedBy, liftedAt, liftReason, active = liftedAt == null,
    )
}
