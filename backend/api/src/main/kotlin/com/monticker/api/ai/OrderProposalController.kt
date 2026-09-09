package com.monticker.api.ai

import com.monticker.api.common.aop.RateLimited
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

data class CreateOrderProposalRequest(val stockId: Long)

@Validated
@RestController
@RequestMapping("/api/ai/order-proposals")
class OrderProposalController(private val orderProposalService: OrderProposalService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @PostMapping
    @RateLimited(limit = 30, windowSec = 3600, keyPrefix = "ai.order-proposal")
    fun create(@RequestBody req: CreateOrderProposalRequest): ResponseEntity<OrderProposalResponse> =
        ResponseEntity.ok(orderProposalService.create(userId(), req.stockId))

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long): ResponseEntity<OrderProposalResponse> =
        ResponseEntity.ok(orderProposalService.approve(userId(), id))

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long): ResponseEntity<OrderProposalResponse> =
        ResponseEntity.ok(orderProposalService.reject(userId(), id))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ResponseEntity<OrderProposalResponse> =
        ResponseEntity.ok(orderProposalService.get(userId(), id))

    @GetMapping
    fun list(@RequestParam(required = false) stockId: Long?): ResponseEntity<List<OrderProposalResponse>> =
        ResponseEntity.ok(orderProposalService.list(userId(), stockId))
}
