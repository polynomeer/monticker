package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.subscription.application.SubscribeResult
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

data class PlanResponse(
    val id: Long,
    val code: String,
    val name: String,
    val price: BigDecimal,
    val currency: String,
    val features: String,
)

data class SubscriptionResponse(
    val planCode: String,
    val planName: String,
    val status: String,
    val startedAt: Instant,
    val expiresAt: Instant?,
)

data class PaymentResponse(
    val id: Long,
    val planCode: String,
    val amount: BigDecimal,
    val status: String,
    val pgTransactionId: String?,
    val paidAt: Instant?,
    val createdAt: Instant,
)

data class SubscribeRequest(val planCode: String)

@RestController
@RequestMapping("/api/subscription")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @GetMapping("/plans")
    fun getPlans(): ResponseEntity<List<PlanResponse>> {
        val plans = subscriptionService.getActivePlans().map { it.toResponse() }
        return ResponseEntity.ok(plans)
    }

    @GetMapping("/me")
    fun getMySubscription(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<SubscriptionResponse> {
        val sub = subscriptionService.getMySubscription(userId(token))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(sub.toResponse())
    }

    @PostMapping("/subscribe")
    fun subscribe(
        @RequestHeader("Authorization") token: String,
        @RequestBody request: SubscribeRequest,
    ): ResponseEntity<SubscribeResult> {
        val planCode = runCatching { PlanCode.valueOf(request.planCode.uppercase()) }
            .getOrElse { return ResponseEntity.badRequest().build() }
        val result = subscriptionService.subscribe(userId(token), planCode)
        return if (result.success) ResponseEntity.ok(result)
               else ResponseEntity.unprocessableEntity().body(result)
    }

    @PostMapping("/cancel")
    fun cancel(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<Void> {
        subscriptionService.cancel(userId(token))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/payments")
    fun getPayments(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<PaymentResponse>> {
        val page = subscriptionService.getPayments(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    private fun SubscriptionPlan.toResponse() = PlanResponse(
        id = id, code = code.name, name = name, price = price, currency = currency, features = features,
    )

    private fun UserSubscription.toResponse() = SubscriptionResponse(
        planCode = plan.code.name, planName = plan.name,
        status = status.name, startedAt = startedAt, expiresAt = expiresAt,
    )

    private fun PaymentRecord.toResponse() = PaymentResponse(
        id = id, planCode = plan.code.name, amount = amount, status = status.name,
        pgTransactionId = pgTransactionId, paidAt = paidAt, createdAt = createdAt,
    )
}
