package com.monticker.worker.marketdata

import com.monticker.worker.common.DistributedLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * candles_1d는 별도 배치 없이 여기서 매 1분봉 flush마다 당일(KST) 행을 함께 upsert한다.
 * ScreenerRepository의 prevClose 조회(candles_1d를 candle_time DESC로 OFFSET 1)가
 * "가장 최근 행 = 진행 중인 오늘, 그 다음 행 = 확정된 전일 종가"를 전제하므로,
 * 장마감 후 한 번만 적재하는 배치로는 장중 내내 전일 대비 등락률이 어긋난다.
 */
@Component
class CandleAggregator(
    private val jdbc: JdbcTemplate,
    txManager: PlatformTransactionManager,
    meterRegistry: MeterRegistry,
) {
    // resilience-plan §E4 / P1-2 — flush 실패는 로그만 남으면 조용히 캔들이 비어간다.
    // 압축 chunk 충돌(ADR-041)이 이 카운터로 드러나야 한다. 알람: CandleFlushFailing.
    private val flushFailed = meterRegistry.counter("candle_flush_failed_total")
    // 캔들 flush 는 틱 리스너 스레드에서 동기 upsert 2건(candles_1m·candles_1d)을 한 트랜잭션으로 한다. 분 경계에서
    // 종목마다 한 번씩 몰리므로, 실시간 p95 꼬리 스파이크(M-002 §4.4)의 후보다. 이 타이머로 flush 비용과 빈도를 직접 본다.
    private val flushTimer = Timer.builder("candle.flush").publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry)

    private val log = LoggerFactory.getLogger(javaClass)
    private val KST = ZoneId.of("Asia/Seoul")
    private val tx = TransactionTemplate(txManager)

    // in-memory OHLCV per (stockId, minute) — ConcurrentHashMap은 현재 단일 스레드 소비를
    // 전제로도 안전장치 차원에서 사용 (Kafka 리스너 concurrency 설정이 바뀌어도 구조적 보장)
    private val state = ConcurrentHashMap<Long, CandleState>()
    // 완결된(분이 넘어간) 캔들을 여기 모아 두고, @Scheduled drainCompleted()가 리스너 스레드 밖에서 배치로 flush 한다.
    // M-002 §4.4: 이전엔 onTick 이 분 경계에서 종목마다 inline 으로 upsert 2건을 해서, 202종목이 동시에 넘어가는 순간
    // 리스너 스레드가 수십 ms 동안 틱을 못 읽고 e2e 꼬리가 500ms~1s 로 튀었다(콜드 스타트에서 특히). 큐 + 배치로 이 경로를 뗐다.
    private val completed = ConcurrentLinkedQueue<CandleState>()

    data class CandleState(
        val stockId: Long,
        val minute: Instant,
        var open: BigDecimal,
        var high: BigDecimal,
        var low: BigDecimal,
        var close: BigDecimal,
        var volume: Long,
    )

    fun onTick(tick: GeneratedTick) {
        val minute = tick.tradeTime.truncatedTo(ChronoUnit.MINUTES)
        val prev = state[tick.stockId]

        if (prev == null || prev.minute != minute) {
            // 이전 분 캔들은 여기서 DB 를 치지 않고 큐에 넣는다 — 실제 upsert 는 drainCompleted()가 배치로 한다.
            prev?.let { completed.add(it) }
            state[tick.stockId] = CandleState(
                stockId = tick.stockId,
                minute  = minute,
                open    = tick.price,
                high    = tick.price,
                low     = tick.price,
                close   = tick.price,
                volume  = tick.volume,
            )
        } else {
            prev.high   = maxOf(prev.high, tick.price)
            prev.low    = minOf(prev.low, tick.price)
            prev.close  = tick.price
            prev.volume += tick.volume
        }
    }

    /** 큐에 쌓인 완결 캔들을 리스너 스레드 밖(스케줄러 스레드)에서 배치로 flush 한다. */
    @Scheduled(fixedDelay = 1_000)
    fun drainCompleted() {
        if (completed.isEmpty()) return
        val batch = ArrayList<CandleState>(minOf(completed.size, 512))
        while (batch.size < 512) { val c = completed.poll() ?: break; batch.add(c) }
        if (batch.isNotEmpty()) {
            val t0 = System.currentTimeMillis()
            flushBatch(batch)
            log.info("[candle-drain] flushed {} candles in {}ms at {}", batch.size, System.currentTimeMillis() - t0, t0)
        }
        if (completed.isNotEmpty()) drainCompleted()   // 512 초과분(202종목 규모에선 한 번에 끝난다)
    }

    private fun flushBatch(batch: List<CandleState>) {
        val sample = Timer.start()
        try {
            // 배치 전체(1m + 1d)를 한 트랜잭션으로 — 부분 커밋으로 두 테이블이 어긋나지 않게. 종목당 최대 1건이라
            // 같은 (stock, day) 행이 배치 안에서 여러 번 겹치지 않는다(겹쳐도 ON CONFLICT 누적은 순서대로 안전).
            tx.executeWithoutResult {
                batchUpsert("candles_1m", batch) { it.minute }
                batchUpsert("candles_1d", batch) { it.minute.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant() }
            }
        } catch (e: Exception) {
            flushFailed.increment()
            log.error("Candle batch flush failed for {} candles: {}", batch.size, e.message)
        } finally {
            sample.stop(flushTimer)
        }
    }

    /** flushAll/onShutdown 대비: 단일 캔들 즉시 flush(리스너 스레드 밖에서만 불린다). */
    private fun flush(c: CandleState) = flushBatch(listOf(c))

    // table은 항상 호출부의 상수 리터럴("candles_1m"/"candles_1d")이라 문자열 보간이 안전하다.
    private fun batchUpsert(table: String, batch: List<CandleState>, bucketOf: (CandleState) -> Instant) {
        jdbc.batchUpdate(
            """
            INSERT INTO $table (stock_id, candle_time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id, candle_time) DO UPDATE SET
                high   = GREATEST($table.high, EXCLUDED.high),
                low    = LEAST($table.low,     EXCLUDED.low),
                close  = EXCLUDED.close,
                volume = $table.volume + EXCLUDED.volume
            """,
            object : BatchPreparedStatementSetter {
                override fun getBatchSize() = batch.size
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val c = batch[i]
                    ps.setLong(1, c.stockId); ps.setTimestamp(2, Timestamp.from(bucketOf(c)))
                    ps.setBigDecimal(3, c.open); ps.setBigDecimal(4, c.high); ps.setBigDecimal(5, c.low)
                    ps.setBigDecimal(6, c.close); ps.setLong(7, c.volume)
                }
            },
        )
    }

    private fun upsertCandle(table: String, stockId: Long, bucketTime: Instant, c: CandleState) {
        jdbc.update(
            """
            INSERT INTO $table (stock_id, candle_time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id, candle_time) DO UPDATE SET
                high   = GREATEST($table.high, EXCLUDED.high),
                low    = LEAST($table.low,     EXCLUDED.low),
                close  = EXCLUDED.close,
                volume = $table.volume + EXCLUDED.volume
            """,
            stockId,
            Timestamp.from(bucketTime),
            c.open, c.high, c.low, c.close,
            c.volume,
        )
    }

    // call at shutdown to flush everything: 큐에 쌓인 완결 캔들 + 아직 진행 중인 현재 분 캔들.
    fun flushAll() {
        drainCompleted()
        val current = state.values.toList()
        if (current.isNotEmpty()) flushBatch(current)
    }

    // 프로세스 종료 시 마지막으로 누적 중이던 캔들을 flush — 없으면 배포·재시작마다 매번
    // 그 시점까지의 분봉/일봉이 통째로 유실된다. flushAll()은 이 시점 이후 재호출되지
    // 않으므로(프로세스가 곧바로 종료) 재진입 문제는 없다.
    @PreDestroy
    fun onShutdown() = flushAll()

    /**
     * 앱 시작 시 1회 실행 — candles_1d에 "오늘 이전" 행이 하나도 없으면(이 fix 이전에 쌓인
     * candles_1m 이력이 있는 상태로 배포되는 경우) 지금까지의 candles_1m을 KST 달력일
     * 기준으로 묶어 한 번에 채운다. 이후에는 flush()의 실시간 upsert가 매일 새 행을
     * 만들어가므로 별도 배치가 필요 없다.
     *
     * 가드를 "오늘 이전"으로 한정하는 이유: initialDelay(5초)가 지나기 전에 이미 첫 tick의
     * flush()가 오늘자 행을 실시간 upsert할 수 있다 — MarketTickScheduler가 fixedDelay=1초로
     * 기동 직후부터 tick을 흘리므로, 분 경계 근처에서 재시작하면 수 초 안에 오늘자 행이
     * 생긴다. 단순히 `COUNT(*) FROM candles_1d > 0`로 가드하면 이 "오늘자 행 1개"만으로
     * 이미 채워졌다고 오판해 과거 이력 백필을 영영 건너뛰게 된다. 실시간 upsert는 항상
     * 오늘 날짜에만 쓰므로, "오늘 이전" 행의 존재 여부로 가드하면 이 경합이 원천적으로
     * 발생하지 않는다.
     *
     * @DistributedLock: CandleAggregator는 role 조건 없는 무조건 @Component라 이 배치를
     * 특정 role에만 묶을 근거가 없다 — docker-compose msa 프로필의 worker-market/event/alert
     * 3개 프로세스가 기동 시 동시에 이 무거운 집계 쿼리를 중복 실행하는 걸 막기 위해
     * StockFundamentalsCollector/InvestorTrendCollector와 동일한 Redis 기반 락을 쓴다.
     */
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    @DistributedLock(name = "candle-1d-backfill", ttlSeconds = 1800)
    fun backfillOnStartup() {
        val todayStart = Instant.now().atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM candles_1d WHERE candle_time < ?",
            Int::class.java,
            Timestamp.from(todayStart),
        ) ?: 0
        if (count > 0) {
            log.info("candles_1d already has {} pre-existing day rows — skipping historical backfill", count)
            return
        }
        // array_agg(...)[1]는 그룹별 전체 배열을 메모리에 구성한 뒤 첫/마지막 원소만 취해
        // 월 단위 1분봉 이력에서 비효율적이다 — DISTINCT ON으로 첫/마지막 값만 스트리밍으로 뽑는다.
        val rows = jdbc.update(
            """
            WITH day_bounds AS (
                SELECT stock_id,
                       date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul') AS day,
                       max(high)   AS high,
                       min(low)    AS low,
                       sum(volume) AS volume
                FROM candles_1m
                GROUP BY stock_id, date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul')
            ),
            day_open AS (
                SELECT DISTINCT ON (stock_id, date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul'))
                    stock_id,
                    date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul') AS day,
                    open
                FROM candles_1m
                ORDER BY stock_id, date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul'), candle_time ASC
            ),
            day_close AS (
                SELECT DISTINCT ON (stock_id, date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul'))
                    stock_id,
                    date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul') AS day,
                    close
                FROM candles_1m
                ORDER BY stock_id, date_trunc('day', candle_time AT TIME ZONE 'Asia/Seoul'), candle_time DESC
            )
            INSERT INTO candles_1d (stock_id, candle_time, open, high, low, close, volume)
            -- b.day는 KST 벽시계 자정(timestamp without time zone)이라, 그대로 timestamptz 컬럼에 넣으면
            -- 세션 타임존(pgjdbc가 JVM 기본 TZ로 맞춘다)으로 재해석된다 — UTC 컨테이너에서는 flush()가
            -- 쓰는 KST 자정과 9시간 어긋난 별도 행이 생겨 하루에 일봉이 두 개가 된다. AT TIME ZONE으로
            -- "이 벽시계는 KST다"를 명시해 flush()와 같은 Instant로 만든다.
            SELECT b.stock_id, b.day AT TIME ZONE 'Asia/Seoul', o.open, b.high, b.low, c.close, b.volume
            FROM day_bounds b
            JOIN day_open  o ON o.stock_id = b.stock_id AND o.day = b.day
            JOIN day_close c ON c.stock_id = b.stock_id AND c.day = b.day
            ON CONFLICT (stock_id, candle_time) DO NOTHING
            """,
        )
        log.info("candles_1d historical backfill from candles_1m complete: {} day-rows inserted", rows)
    }
}
