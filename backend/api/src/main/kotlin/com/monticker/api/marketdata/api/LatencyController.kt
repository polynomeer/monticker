package com.monticker.api.marketdata.api

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/latency")
class LatencyController(private val meterRegistry: MeterRegistry) {

    @GetMapping
    fun getLatency(): ResponseEntity<Map<String, Any>> {
        fun timerStats(name: String): Map<String, Double> {
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
            "redisWrite"    to timerStats("tick.latency.redis_write"),
            "dbWrite"       to timerStats("tick.latency.db_write"),
            "broadcast"     to timerStats("tick.latency.broadcast"),
            "totalPipeline" to timerStats("tick.latency.total_pipeline"),
        ))
    }
}
