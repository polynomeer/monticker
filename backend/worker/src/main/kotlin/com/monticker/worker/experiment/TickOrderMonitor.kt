package com.monticker.worker.experiment

import com.monticker.worker.marketdata.GeneratedTick
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 실험 M-002(파티션 수 × 컨슈머 수에 따른 종목별 순서 보장) 전용 관측기 — `experiment` 프로파일에서만 뜬다.
 *
 * Go market-gateway가 TICK_SEQ=true 로 붙인 종목별 시퀀스(1, 2, 3, …)를 TickKafkaConsumer가 받는 순서 그대로
 * 대조한다. 종목마다 지금까지 본 최대 seq(`max`)와 최근 본 seq 집합(`seen`, 창 크기 제한)을 들고:
 *   - seq == 이미 본 값        → dup        (리밸런스 후 미커밋 오프셋 재전달, at-least-once)
 *   - seq <  max, 못 본 값     → violation  (순서 뒤바뀜 — 키 없이 라운드로빈으로 뿌리면 여기서 나온다)
 *   - seq >  max + 1           → gap        (seq-max-1 건. 유실이거나 아직 안 온 것. 나중에 오면 violation으로 잡히므로
 *                                             "유실 ≈ gap − violation" 으로 읽는다)
 * 그리고 틱마다 generatedAt → 지금 까지의 e2e 지연을 핫 종목 여부·핫 종목과 같은 파티션 여부로 나눠 쌓아 둔다
 * (M-002(c) head-of-line: 핫 종목과 파티션을 공유하는 종목만 늦어지는가). 분위수는 요약이 아니라 전수 샘플에서
 * 정확히 계산한다 — 실험 1회가 12만 틱 정도라 메모리는 문제가 아니다.
 *
 * `slow-stock-id`/`slow-ms` 가 설정되면 그 종목의 틱을 처리할 때 sleep 한다 — 핫 종목이 "느린" 종목이기도 한
 * 상황(대형주 1건당 처리 비용이 큰 경우)을 흉내 낸다. 리스너 스레드를 붙잡으므로 같은 파티션의 다른 종목이
 * 함께 밀린다 — 그게 이 실험이 보려는 것이다.
 *
 * 카운터는 Micrometer에도 올리지만(experiment_tick_*), 실험 스크립트는 GET /experiment/tick-order 의 정확한
 * 숫자를 쓴다. POST /experiment/tick-order/reset 으로 실행(run) 사이를 끊는다.
 *
 * `redis-log=true` 면 틱마다 Redis 스트림 `experiment:tick-order` 에 (stock, seq, partition, worker, generatedAt, receivedAt)
 * 을 XADD 한다 — 리밸런스 실험(M-002(b))처럼 worker 프로세스가 둘 이상이거나 하나가 죽어 메모리 상태가 사라질 때,
 * 프로세스 밖에서 전 구간을 다시 세기 위해서다(bench/experiments/m2-analyze-stream.py). 틱당 Redis 왕복 하나가
 * 파이프라인에 얹히므로 e2e 수치를 볼 실험에서는 끈다.
 */
@Component
@Profile("experiment")
class TickOrderMonitor(
    meterRegistry: MeterRegistry,
    @Value("\${experiment.tick-order.hot-stock-id:0}") private val hotStockId: Long,
    @Value("\${experiment.tick-order.slow-stock-id:0}") private val slowStockId: Long,
    @Value("\${experiment.tick-order.slow-ms:0}") private val slowMs: Long,
    @Value("\${experiment.tick-order.redis-log:false}") private val redisLog: Boolean,
    @Value("\${experiment.tick-order.worker-id:w}") private val workerId: String,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class StockState {
        var max: Long = 0
        val seen = LinkedHashSet<Long>()       // 최근 SEEN_WINDOW 개 — 오래된 것부터 버린다
    }

    private val states = ConcurrentHashMap<Long, StockState>()
    private val partitionOf = ConcurrentHashMap<Long, Int>()
    private val ticks = AtomicLong(); private val withSeq = AtomicLong()
    private val violations = AtomicLong(); private val dups = AtomicLong(); private val gaps = AtomicLong()
    private val cViolations = meterRegistry.counter("experiment_tick_order_violations_total")
    private val cDups = meterRegistry.counter("experiment_tick_dups_total")
    private val cGaps = meterRegistry.counter("experiment_tick_gaps_total")
    private val startedAt = AtomicLong(System.currentTimeMillis())

    // 클래스별 e2e 샘플(ms). hot / same_partition_as_hot / other
    private val samples = ConcurrentHashMap<String, MutableList<Double>>()
    private val firstViolations = ArrayList<String>()

    init {
        log.warn("[EXPERIMENT] TickOrderMonitor 활성 — hotStockId={} slowStockId={} slowMs={} redisLog={} workerId={}", hotStockId, slowStockId, slowMs, redisLog, workerId)
    }

    fun observe(partition: Int, tick: GeneratedTick) {
        ticks.incrementAndGet()
        partitionOf[tick.stockId] = partition
        val seq = tick.seq
        if (seq != null) {
            withSeq.incrementAndGet()
            val st = states.computeIfAbsent(tick.stockId) { StockState() }
            synchronized(st) {
                when {
                    st.seen.contains(seq) -> { dups.incrementAndGet(); cDups.increment() }
                    seq < st.max -> {
                        violations.incrementAndGet(); cViolations.increment()
                        synchronized(firstViolations) {
                            if (firstViolations.size < 20) firstViolations += "stock=${tick.stockId} p=$partition seq=$seq after max=${st.max}"
                        }
                    }
                    seq > st.max + 1 && st.max != 0L -> { val g = seq - st.max - 1; gaps.addAndGet(g); cGaps.increment(g.toDouble()) }
                }
                if (seq > st.max) st.max = seq
                st.seen += seq
                if (st.seen.size > SEEN_WINDOW) { val it = st.seen.iterator(); it.next(); it.remove() }
            }
        }
        val e2eMs = Duration.between(tick.generatedAt, Instant.now()).toNanos() / 1_000_000.0
        val cls = when {
            tick.stockId == hotStockId -> "hot"
            hotStockId != 0L && partitionOf[hotStockId] == partition -> "same_partition_as_hot"
            else -> "other"
        }
        samples.computeIfAbsent(cls) { java.util.Collections.synchronizedList(ArrayList(200_000)) }.add(e2eMs)
        if (redisLog) {
            redis.opsForStream<String, String>().add(STREAM, mapOf(
                "s" to tick.stockId.toString(), "q" to (seq ?: -1).toString(), "p" to partition.toString(),
                "w" to workerId, "g" to tick.generatedAt.toEpochMilli().toString(), "r" to System.currentTimeMillis().toString(),
            ))
        }

        if (slowStockId != 0L && tick.stockId == slowStockId && slowMs > 0) Thread.sleep(slowMs)
    }

    fun snapshot(): Map<String, Any?> {
        val perClass = samples.mapValues { (_, list) ->
            val arr = synchronized(list) { list.toDoubleArray() }.also { it.sort() }
            fun q(p: Double) = if (arr.isEmpty()) null else arr[((arr.size - 1) * p).toInt()]
            mapOf("n" to arr.size, "p50_ms" to q(0.5), "p95_ms" to q(0.95), "p99_ms" to q(0.99), "max_ms" to arr.lastOrNull())
        }
        return mapOf(
            "since_ms" to System.currentTimeMillis() - startedAt.get(),
            "ticks" to ticks.get(), "ticks_with_seq" to withSeq.get(),
            "stocks" to states.size,
            "violations" to violations.get(), "dups" to dups.get(), "gaps" to gaps.get(),
            "hot_stock_id" to hotStockId, "hot_partition" to partitionOf[hotStockId],
            "partitions_seen" to partitionOf.values.toSortedSet().toList(),
            "e2e" to perClass,
            "first_violations" to synchronized(firstViolations) { firstViolations.toList() },
        )
    }

    fun reset() {
        states.clear(); partitionOf.clear(); samples.clear()
        ticks.set(0); withSeq.set(0); violations.set(0); dups.set(0); gaps.set(0)
        synchronized(firstViolations) { firstViolations.clear() }
        startedAt.set(System.currentTimeMillis())
    }

    companion object {
        private const val SEEN_WINDOW = 4096
        const val STREAM = "experiment:tick-order"
    }
}
