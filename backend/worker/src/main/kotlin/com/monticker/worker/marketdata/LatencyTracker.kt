package com.monticker.worker.marketdata

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Component
class LatencyTracker(private val meterRegistry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    // tick 생성 시각 저장 (stockId → generatedAt)
    private val tickTimestamps = ConcurrentHashMap<Long, Instant>()

    private val redisWriteTimer    = meterRegistry.timer("tick.latency.redis_write")
    private val dbWriteTimer       = meterRegistry.timer("tick.latency.db_write")
    private val broadcastTimer     = meterRegistry.timer("tick.latency.broadcast")
    private val totalPipelineTimer = meterRegistry.timer("tick.latency.total_pipeline")

    private val tickCount = AtomicLong(0)

    fun recordTickGenerated(stockId: Long, generatedAt: Instant) {
        tickTimestamps[stockId] = generatedAt
        tickCount.incrementAndGet()
    }

    fun recordRedisWrite(stockId: Long) {
        tickTimestamps[stockId]?.let { generated ->
            val latency = Duration.between(generated, Instant.now())
            redisWriteTimer.record(latency)
        }
    }

    fun recordDbWrite(stockId: Long) {
        tickTimestamps[stockId]?.let { generated ->
            val latency = Duration.between(generated, Instant.now())
            dbWriteTimer.record(latency)
        }
    }

    fun recordBroadcast(stockId: Long) {
        tickTimestamps[stockId]?.let { generated ->
            val latency = Duration.between(generated, Instant.now())
            broadcastTimer.record(latency)
            totalPipelineTimer.record(latency)
            if (latency.toMillis() > 100) {
                log.warn("시세 파이프라인 지연 경고: stockId={} latency={}ms", stockId, latency.toMillis())
            }
            tickTimestamps.remove(stockId)
        }
    }

    fun summary(): Map<String, Any> = mapOf(
        "totalTicks"          to tickCount.get(),
        "redisWrite_p50ms"    to redisWriteTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "redisWrite_p99ms"    to redisWriteTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "dbWrite_p50ms"       to dbWriteTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "dbWrite_p99ms"       to dbWriteTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "broadcast_p50ms"     to broadcastTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "broadcast_p99ms"     to broadcastTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "totalPipeline_p50ms" to totalPipelineTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "totalPipeline_p99ms" to totalPipelineTimer.percentile(0.99, TimeUnit.MILLISECONDS),
    )
}
