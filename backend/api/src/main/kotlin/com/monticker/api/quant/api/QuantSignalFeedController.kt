package com.monticker.api.quant.api

import com.monticker.api.quant.application.QuantSignalFeedItem
import com.monticker.api.quant.application.QuantSignalFeedService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 내 룰셋·구독 전략의 포워드 테스트 신호 피드(ADR-035 접근 규칙). 로그인 필요.
 *
 * GET /api/quant/signals/feed?limit=20
 */
@RestController
@RequestMapping("/api/quant/signals")
class QuantSignalFeedController(private val service: QuantSignalFeedService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/feed")
    fun feed(@RequestParam(defaultValue = "20") limit: Int): ResponseEntity<List<QuantSignalFeedItem>> =
        ResponseEntity.ok(service.feed(userId(), limit))
}
