package com.monticker.api.quant.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.common.aop.Timed
import com.monticker.api.quant.application.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/quant/rulesets")
class RuleSetController(private val service: RuleSetService) {

    private fun userId(): Long {
        val attrs = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
            as org.springframework.web.context.request.ServletRequestAttributes
        return attrs.request.getHeader("X-User-Id")?.toLong()
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "X-User-Id header required")
    }

    @PostMapping
    fun create(@RequestBody req: CreateRuleSetRequest): ResponseEntity<RuleSetResponse> =
        ResponseEntity.ok(service.create(userId(), req))

    @GetMapping
    fun list(): ResponseEntity<List<RuleSetResponse>> =
        ResponseEntity.ok(service.findByUser(userId()))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.findById(id, userId()))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody req: UpdateRuleSetRequest,
    ): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.update(id, userId(), req))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        return try {
            service.delete(id, userId())
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/{id}/backtest")
    @Timed("quant.backtest", tags = ["module=quant"])
    @RateLimited(limit = 10, windowSec = 3600, keyPrefix = "quant.backtest")
    fun runBacktest(
        @PathVariable id: Long,
        @RequestBody req: QuantBacktestRequest,
    ): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.runBacktest(id, userId(), req))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    @GetMapping("/{id}/backtest")
    fun listBacktests(@PathVariable id: Long): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.listBacktestResults(id, userId()))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
}
