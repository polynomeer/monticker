package com.monticker.api.quant.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.common.aop.Timed
import com.monticker.api.quant.application.*
import com.monticker.api.quant.domain.RuleVersionEntry
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/quant/rulesets")
class RuleSetController(private val service: RuleSetService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @PostMapping
    fun create(@RequestBody req: CreateRuleSetRequest): ResponseEntity<RuleSetResponse> =
        ResponseEntity.ok(service.create(userId(), req))

    @GetMapping
    fun list(): ResponseEntity<List<RuleSetResponse>> =
        ResponseEntity.ok(service.findByUser(userId()))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.findById(id, userId()))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody req: UpdateRuleSetRequest,
    ): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.update(id, userId(), req))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> =
        try {
            service.delete(id, userId())
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }

    @GetMapping("/{id}/versions")
    fun versions(@PathVariable id: String): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.getVersionHistory(id, userId()))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @PostMapping("/{id}/backtest")
    @Timed("quant.backtest", tags = ["module=quant"])
    @RateLimited(limit = 10, windowSec = 3600, keyPrefix = "quant.backtest")
    fun runBacktest(
        @PathVariable id: String,
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
    fun listBacktests(@PathVariable id: String): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.listBacktestResults(id, userId()))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
}
