package com.monticker.api.quant.api

import com.monticker.api.quant.application.ForwardTestService
import com.monticker.api.quant.application.StartForwardTestRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/quant/rulesets/{id}/forward-test")
class ForwardTestController(private val service: ForwardTestService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @PostMapping("/start")
    fun start(
        @PathVariable id: String,
        @RequestBody req: StartForwardTestRequest,
    ): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.start(id, userId(), req))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    @PostMapping("/stop")
    fun stop(@PathVariable id: String): ResponseEntity<*> =
        try {
            ResponseEntity.ok(service.stop(id, userId()))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    @GetMapping
    fun status(@PathVariable id: String): ResponseEntity<*> =
        try {
            val result = service.getStatus(id, userId())
            if (result == null) ResponseEntity.noContent().build<Unit>() else ResponseEntity.ok(result)
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        }
}
