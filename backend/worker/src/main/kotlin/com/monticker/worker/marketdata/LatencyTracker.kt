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
    private val slowSinceLog   = AtomicLong(0)
    private val lastSlowLog    = AtomicLong(0)
    private val slowTicks      = meterRegistry.counter("tick_pipeline_slow_total")   // 100ms 초과 틱 수

    private fun timer(name: String): Timer =
        Timer.builder(name)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

    private val redisTimer     = timer("tick.latency.redis_write")
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

    fun recordBroadcast(stockId: Long) {
        tickTimestamps[stockId]?.let { generated ->
            val latency = Duration.between(generated, Instant.now())
            broadcastTimer.record(latency)
            totalTimer.record(latency)
            if (latency.toMillis() > 100) {
                // 틱마다 WARN을 찍으면 백로그 상황에서 로그 폭풍이 된다 — L-03 재측정에서 60초에 133,749줄이
                // 찍히며 처리량이 절반으로 떨어지는 자기강화 루프(밀림 → 매 틱 경고 → 더 밀림)를 확인했다.
                // 10초에 한 번만 남기고 그동안 몇 건이었는지 같이 적는다. 추이는 slow_ticks 카운터로 본다.
                slowTicks.increment()
                val n = slowSinceLog.incrementAndGet()
                val now = System.currentTimeMillis()
                val last = lastSlowLog.get()
                if (now - last > 10_000 && lastSlowLog.compareAndSet(last, now)) {
                    log.warn("[Latency] 파이프라인 지연 경고: 최근 10초간 {}건이 100ms 초과 (마지막 stockId={} latency={}ms)",
                        n, stockId, latency.toMillis())
                    slowSinceLog.set(0)
                }
            }
            tickTimestamps.remove(stockId)
        }
    }

    fun summary(): Map<String, Any> = mapOf(
        "totalTicks"          to tickCount.get(),
        "redisWrite_p50ms"    to redisTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "redisWrite_p99ms"    to redisTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "broadcast_p50ms"     to broadcastTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "broadcast_p99ms"     to broadcastTimer.percentile(0.99, TimeUnit.MILLISECONDS),
        "totalPipeline_p50ms" to totalTimer.percentile(0.5,  TimeUnit.MILLISECONDS),
        "totalPipeline_p99ms" to totalTimer.percentile(0.99, TimeUnit.MILLISECONDS),
    )
}
