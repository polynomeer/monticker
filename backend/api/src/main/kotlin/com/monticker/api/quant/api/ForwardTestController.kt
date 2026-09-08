package com.monticker.api.quant.api

import com.monticker.api.quant.application.ForwardTestResponse
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
    ): ResponseEntity<ForwardTestResponse> =
        ResponseEntity.ok(service.start(id, userId(), req))

    @PostMapping("/stop")
    fun stop(@PathVariable id: String): ResponseEntity<ForwardTestResponse> =
        ResponseEntity.ok(service.stop(id, userId()))

    @GetMapping
    fun status(@PathVariable id: String): ResponseEntity<ForwardTestResponse> {
        val result = service.getStatus(id, userId())
        return if (result == null) ResponseEntity.noContent().build() else ResponseEntity.ok(result)
    }
}
