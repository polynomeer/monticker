package com.monticker.worker.marketdata

import com.monticker.worker.common.DistributedLock
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val KST = ZoneId.of("Asia/Seoul")
    private val tx = TransactionTemplate(txManager)

    // in-memory OHLCV per (stockId, minute) — ConcurrentHashMap은 현재 단일 스레드 소비를
    // 전제로도 안전장치 차원에서 사용 (Kafka 리스너 concurrency 설정이 바뀌어도 구조적 보장)
    private val state = ConcurrentHashMap<Long, CandleState>()

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
            // flush previous candle if exists
            prev?.let { flush(it) }
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

    private fun flush(c: CandleState) {
        val dayStart = c.minute.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()
        try {
            // 두 upsert가 한쪽만 커밋된 채 어긋나지 않도록 하나의 트랜잭션으로 묶는다.
            tx.executeWithoutResult {
                upsertCandle("candles_1m", c.stockId, c.minute, c)
                upsertCandle("candles_1d", c.stockId, dayStart, c)
            }
        } catch (e: Exception) {
            log.error("Candle flush failed for stock {}: {}", c.stockId, e.message)
        }
    }

    // table은 항상 호출부의 상수 리터럴("candles_1m"/"candles_1d")이라 문자열 보간이 안전하다.
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

    // call at shutdown or periodically to flush current-minute candles
    fun flushAll() = state.values.forEach { flush(it) }

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
            SELECT b.stock_id, b.day, o.open, b.high, b.low, c.close, b.volume
            FROM day_bounds b
            JOIN day_open  o ON o.stock_id = b.stock_id AND o.day = b.day
            JOIN day_close c ON c.stock_id = b.stock_id AND c.day = b.day
            ON CONFLICT (stock_id, candle_time) DO NOTHING
            """,
        )
        log.info("candles_1d historical backfill from candles_1m complete: {} day-rows inserted", rows)
    }
}
