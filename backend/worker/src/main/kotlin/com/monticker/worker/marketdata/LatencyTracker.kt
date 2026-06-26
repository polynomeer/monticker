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

/**
 * 시세 파이프라인 각 단계의 지연을 측정한다.
 *
 * 측정 구간:
 *   tick 생성 → Redis 쓰기 → DB 쓰기 → 브로드캐스트 발신
 *
 * 결과는 Micrometer Timer로 기록되어 Actuator /actuator/metrics 및
 * GET /api/latency 엔드포인트를 통해 p50/p95/p99 로 조회할 수 있다.
 */
@Component
class LatencyTracker(private val meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val tickTimestamps = ConcurrentHashMap<Long, Instant>()
    private val tickCount      = AtomicLong(0)

    private fun timer(name: String): Timer =
        Timer.builder(name)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

    private val redisTimer     = timer("tick.latency.redis_write")
    private val dbTimer        = timer("tick.latency.db_write")
    private val broadcastTimer = timer("tick.latency.broadcast")
    private val totalTimer     = timer("tick.latency.total_pipeline")

    fun recordTickGenerated(stockId: Long, generatedAt: Instant) {
        tickTimestamps[stockId] = generatedAt
        tickCount.incrementAndGet()
    }

    fun recordRedisWrite(stockId: Long) {
        tickTimestamps[stockId]?.let {
            redisTimer.record(Duration.between(it, Instant.now()))
        }
    }

    fun recordDbWrite(stockId: Long) {
        tickTimestamps[stockId]?.let {
            dbTimer.record(Duration.between(it, Instant.now()))
        }
    }

    fun recordBroadcast(stockId: Long) {
        tickTimestamps[stockId]?.let { generated ->
            val latency = Duration.between(generated, Instant.now())
            broadcastTimer.record(latency)
            totalTimer.record(latency)
            if (latency.toMillis() > 100) {
                log.warn("[Latency] 파이프라인 지연 경고: stockId={} latency={}ms",
                    stockId, latency.toMillis())
            }
            tickTimestamps.remove(stockId)
        }
    }

    fun summary(): Map<String, Any> = mapOf(
        "totalTicks"          to tickCount.get(),
        "redisWrite_p50ms"    to redisTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "redisWrite_p99ms"    to redisTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "dbWrite_p50ms"       to dbTimer.percentile(0.5,     TimeUnit.MILLISECONDS),
        "dbWrite_p99ms"       to dbTimer.percentile(0.99,    TimeUnit.MILLISECONDS),
        "broadcast_p50ms"     to broadcastTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "broadcast_p99ms"     to broadcastTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "totalPipeline_p50ms" to totalTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "totalPipeline_p99ms" to totalTimer.percentile(0.99, TimeUnit.MILLISECONDS),
    )
}
