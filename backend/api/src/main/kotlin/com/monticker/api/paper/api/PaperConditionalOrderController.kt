package com.monticker.api.paper.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.paper.application.PaperConditionalOrderResponse
import com.monticker.api.paper.application.PaperConditionalOrderService
import com.monticker.api.paper.application.PaperConditionalRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

/** ADR-075 — 모의투자 조건부 주문(익절·손절·가격 도달, OCO). 발동은 모의 매칭 엔진 시장가 주문뿐이다. */
@RestController
@RequestMapping("/api/paper/conditional-orders")
class PaperConditionalOrderController(private val service: PaperConditionalOrderService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping
    fun list(@RequestParam(required = false) stockId: Long?): ResponseEntity<List<PaperConditionalOrderResponse>> =
        ResponseEntity.ok(service.list(userId(), stockId))

    @PostMapping
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "paper.conditional")
    fun create(@RequestBody req: PaperConditionalRequest): ResponseEntity<List<PaperConditionalOrderResponse>> =
        ResponseEntity.ok(service.create(userId(), req))

    @DeleteMapping("/{id}")
    @RateLimited(limit = 60, windowSec = 60, keyPrefix = "paper.conditional.cancel")
    fun cancel(@PathVariable id: Long): ResponseEntity<PaperConditionalOrderResponse> =
        ResponseEntity.ok(service.cancel(userId(), id))
}
