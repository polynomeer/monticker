package com.monticker.api.quant.api

import com.monticker.api.quant.application.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/quant/rulesets")
class RuleSetController(private val service: RuleSetService) {

    // TODO: replace hardcoded userId with JWT principal after auth is wired up
    private val tempUserId = 1L

    @PostMapping
    fun create(@RequestBody req: CreateRuleSetRequest): ResponseEntity<RuleSetResponse> =
        ResponseEntity.ok(service.create(tempUserId, req))

    @GetMapping
    fun list(): ResponseEntity<List<RuleSetResponse>> =
        ResponseEntity.ok(service.findByUser(tempUserId))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.findById(id, tempUserId))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody req: UpdateRuleSetRequest,
    ): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.update(id, tempUserId, req))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        return try {
            service.delete(id, tempUserId)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/{id}/backtest")
    fun runBacktest(
        @PathVariable id: Long,
        @RequestBody req: QuantBacktestRequest,
    ): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.runBacktest(id, tempUserId, req))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    @GetMapping("/{id}/backtest")
    fun listBacktests(@PathVariable id: Long): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.listBacktestResults(id, tempUserId))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
}
