package com.monticker.api.marketdata.api

import com.monticker.api.common.redis.RedisGuard
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ADR-039 — 시장 요약 REST 폴백. worker가 1초마다 Redis market:summary(TTL 5s)에 쓴 JSON을 그대로 돌려준다.
 * 초기 렌더와 WebSocket 재연결 사이를 메운다. Redis가 없으면 204 — 위젯은 빈 상태를 그린다(fail-open).
 */
@RestController
@RequestMapping("/api/market")
class MarketSummaryController(
    private val redis: StringRedisTemplate,
    private val guard: RedisGuard,
) {
    @GetMapping("/summary", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun summary(): ResponseEntity<String> {
        val json = guard.failOpen(op = "market_summary_get", fallback = null as String?) {
            redis.opsForValue().get("market:summary")
        }
        return if (json == null) ResponseEntity.noContent().build() else ResponseEntity.ok(json)
    }
}
