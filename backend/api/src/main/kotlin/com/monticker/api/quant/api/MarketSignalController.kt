package com.monticker.api.quant.api

import com.monticker.api.quant.application.MarketSignalFeed
import com.monticker.api.quant.application.MarketSignalItem
import com.monticker.api.quant.application.MarketSignalQueryService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

/** 구독 전략 신호 이력 — 접근 제어는 MarketSignalQueryService(ADR-035). */
@RestController
@RequestMapping("/api/quant/market")
class MarketSignalController(private val service: MarketSignalQueryService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    /** 내가 구독 중인 전략들의 최근 신호 + 이번 달 신호 수 */
    @GetMapping("/signals")
    fun subscribedFeed(@RequestParam(defaultValue = "30") limit: Int): ResponseEntity<MarketSignalFeed> =
        ResponseEntity.ok(service.subscribedFeed(userId(), limit))

    /** 전략 하나의 신호 이력 — 제작자·구독자만 */
    @GetMapping("/{id}/signals")
    fun strategyHistory(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "30") limit: Int,
    ): ResponseEntity<List<MarketSignalItem>> =
        ResponseEntity.ok(service.strategyHistory(userId(), id, limit))
}
