package com.monticker.api.community.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.community.application.StockCommentResponse
import com.monticker.api.community.application.StockCommentService
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

data class CreateCommentRequest(val content: String, val eventId: Long? = null)
data class ReportCommentRequest(val reason: String)

@Validated
@RestController
@RequestMapping("/api/stocks/{stockId}/comments")
class StockCommentController(private val stockCommentService: StockCommentService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @PostMapping
    @RateLimited(limit = 5, windowSec = 60, keyPrefix = "community.comment")
    fun create(@PathVariable stockId: Long, @RequestBody req: CreateCommentRequest): ResponseEntity<StockCommentResponse> =
        ResponseEntity.ok(stockCommentService.create(userId(), stockId, req.content, req.eventId))

    @GetMapping
    fun list(
        @PathVariable stockId: Long,
        @RequestParam(required = false) eventId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<List<StockCommentResponse>> =
        ResponseEntity.ok(stockCommentService.list(stockId, eventId, PageRequest.of(page, size)))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable stockId: Long, @PathVariable id: Long): ResponseEntity<Unit> {
        stockCommentService.delete(userId(), id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/report")
    fun report(@PathVariable stockId: Long, @PathVariable id: Long, @RequestBody req: ReportCommentRequest): ResponseEntity<Unit> {
        stockCommentService.report(userId(), id, req.reason)
        return ResponseEntity.noContent().build()
    }
}
