package com.monticker.api.marketdata.api

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

/**
 * 시세 파이프라인 지연 통계 조회 엔드포인트.
 *
 * Worker의 LatencyTracker가 기록한 Micrometer Timer 값을
 * p50/p95/p99/mean (밀리초) 형태로 반환한다.
 *
 * 측정 구간:
 *   - redisWrite   : tick 생성 → Redis SET 완료
 *   - dbWrite      : tick 생성 → price_ticks INSERT 완료
 *   - broadcast    : tick 생성 → WebSocket 발신 완료
 *   - totalPipeline: tick 생성 → 브로드캐스트 (전체)
 */
@RestController
@RequestMapping("/api/latency")
class LatencyController(private val meterRegistry: MeterRegistry) {

    @GetMapping
    fun getLatency(): ResponseEntity<Map<String, Any>> {

        fun stats(name: String): Map<String, Double> {
            val timer = meterRegistry.find(name).timer()
                ?: return mapOf("count" to 0.0, "p50" to 0.0, "p95" to 0.0, "p99" to 0.0, "mean" to 0.0)
            return mapOf(
                "count" to timer.count().toDouble(),
                "p50"   to timer.percentile(0.5,  TimeUnit.MILLISECONDS),
                "p95"   to timer.percentile(0.95, TimeUnit.MILLISECONDS),
                "p99"   to timer.percentile(0.99, TimeUnit.MILLISECONDS),
                "mean"  to timer.mean(TimeUnit.MILLISECONDS),
            )
        }

        return ResponseEntity.ok(mapOf(
            "redisWrite"    to stats("tick.latency.redis_write"),
            "dbWrite"       to stats("tick.latency.db_write"),
            "broadcast"     to stats("tick.latency.broadcast"),
            "totalPipeline" to stats("tick.latency.total_pipeline"),
            "_note"         to "단위: ms. Worker가 실행 중이어야 값이 채워집니다.",
        ))
    }
}
