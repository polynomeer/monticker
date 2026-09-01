package com.monticker.worker.marketdata

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-021: candles_1d는 별도 배치가 아니라 CandleAggregator.flush()의 실시간 upsert로
 * 채워진다. 이 테스트는 그 upsert가 여러 KST 달력일에 걸쳐 온 tick들을 올바른 OHLCV로
 * 굴려 담는지, 그리고 ScreenerRepository가 의존하는 "최신 행=진행 중인 오늘,
 * OFFSET 1=확정된 전일 종가" 전제가 실제로 성립하는지를 검증한다.
 *
 * 스키마는 worker가 아닌 api가 소유하므로(worker의 flyway.enabled=false), 여기서는
 * api 모듈의 마이그레이션 디렉터리를 파일시스템 경로로 직접 가리켜 Flyway를 돌린다 —
 * worker가 api에 컴파일 의존성을 갖지 않으면서도 실제 스키마 위에서 검증하기 위함.
 */
@Testcontainers
class CandleAggregatorIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")

        lateinit var jdbc: JdbcTemplate

        @JvmStatic
        @BeforeAll
        fun migrate() {
            val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { setDriverClassName(postgres.driverClassName) }
            Flyway.configure()
                .dataSource(ds)
                .locations("filesystem:../api/src/main/resources/db/migration")
                .load()
                .migrate()
            jdbc = JdbcTemplate(ds)
        }
    }

    private val KST = ZoneId.of("Asia/Seoul")

    // candles_1d의 count==0 가드(backfillOnStartup)를 검증해야 하므로 테스트마다 캔들 테이블을 비운다.
    @BeforeEach
    fun cleanCandles() {
        jdbc.update("TRUNCATE candles_1m, candles_1d")
    }

    private fun insertStock(symbol: String): Long = jdbc.queryForObject(
        """
        INSERT INTO stocks (symbol, name, market, exchange, sector, country, currency, is_active)
        VALUES (?, ?, 'KOSPI', 'KRX', 'IT', 'KR', 'KRW', true)
        RETURNING id
        """,
        Long::class.java,
        symbol, "$symbol Inc",
    )!!

    private fun tick(stockId: Long, day: LocalDate, hour: Int, minute: Int, price: Long, volume: Long) =
        GeneratedTick(
            stockId = stockId,
            symbol = "TST",
            market = "KOSPI",
            price = BigDecimal(price),
            volume = volume,
            tradeTime = day.atStartOfDay(KST).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant(),
        )

    @Test
    fun `flush rolls minute ticks into a same-day candles_1d row across multiple minutes`() {
        val aggregator = CandleAggregator(jdbc)
        val stockId = insertStock("ROLL1")
        val day = LocalDate.of(2026, 8, 31)

        // 09:00 분봉: 100 -> 105 -> 98 (open=100, high=105, low=98, close=98, volume=45)
        aggregator.onTick(tick(stockId, day, 9, 0, 100, 10))
        aggregator.onTick(tick(stockId, day, 9, 0, 105, 20))
        aggregator.onTick(tick(stockId, day, 9, 0, 98, 15))
        // 09:01 분봉으로 넘어가면서 09:00 분봉이 flush된다
        aggregator.onTick(tick(stockId, day, 9, 1, 110, 5))
        aggregator.flushAll()

        val d1 = jdbc.queryForMap(
            "SELECT open, high, low, close, volume FROM candles_1d WHERE stock_id = ?",
            stockId,
        )
        assertThat((d1["open"] as BigDecimal).toInt()).isEqualTo(100)
        assertThat((d1["high"] as BigDecimal).toInt()).isEqualTo(110)
        assertThat((d1["low"] as BigDecimal).toInt()).isEqualTo(98)
        assertThat((d1["close"] as BigDecimal).toInt()).isEqualTo(110)
        assertThat((d1["volume"] as Number).toLong()).isEqualTo(50L)

        val dayCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM candles_1d WHERE stock_id = ?", Int::class.java, stockId,
        )
        assertThat(dayCount).isEqualTo(1)
    }

    @Test
    fun `screener-style prevClose query returns yesterday's confirmed close while today's row is still live`() {
        val aggregator = CandleAggregator(jdbc)
        val stockId = insertStock("ROLL2")
        val yesterday = LocalDate.of(2026, 8, 31)
        val today = LocalDate.of(2026, 9, 1)

        // 전일: 09:00 100->102, 09:01 102->99 (전일 종가 확정 99)
        aggregator.onTick(tick(stockId, yesterday, 9, 0, 100, 10))
        aggregator.onTick(tick(stockId, yesterday, 9, 0, 102, 10))
        aggregator.onTick(tick(stockId, yesterday, 9, 1, 99, 10))
        aggregator.flushAll()

        // 금일: 09:00 200 (당일 진행 중 — 아직 장중)
        aggregator.onTick(tick(stockId, today, 9, 0, 200, 30))
        aggregator.flushAll()

        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM candles_1d WHERE stock_id = ?", Int::class.java, stockId),
        ).isEqualTo(2)

        // ScreenerRepository.findItems()와 동일한 LATERAL 조회 — c=candles_1m 최신가(현재가),
        // prev=candles_1d를 candle_time DESC OFFSET 1 (가장 최근 행=진행 중인 오늘, 그 앞=확정 전일)
        val row = jdbc.queryForMap(
            """
            SELECT c.close AS price, prev.close AS prev_close
            FROM stocks s
            LEFT JOIN LATERAL (
                SELECT close FROM candles_1m WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1
            ) c ON true
            LEFT JOIN LATERAL (
                SELECT close FROM candles_1d WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1 OFFSET 1
            ) prev ON true
            WHERE s.id = ?
            """,
            stockId,
        )

        val price = (row["price"] as BigDecimal)
        val prevClose = (row["prev_close"] as BigDecimal)
        assertThat(price.toInt()).isEqualTo(200)
        assertThat(prevClose.toInt()).isEqualTo(99)

        val changeRate = price.subtract(prevClose).divide(prevClose, 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
        // (200 - 99) / 99 * 100 ~= 102.02% — 버그 이전이었다면 candles_1d가 항상 비어
        // prevClose가 null -> changeRate가 항상 0으로 떨어졌을 값
        assertThat(changeRate.toDouble()).isGreaterThan(100.0)
    }

    @Test
    fun `backfillOnStartup groups existing candles_1m history into candles_1d by KST day when the table is empty`() {
        val stockId = insertStock("BFILL1")
        val day1 = LocalDate.of(2026, 8, 20)
        val day2 = LocalDate.of(2026, 8, 21)

        fun insertMinuteCandle(day: LocalDate, hour: Int, minute: Int, o: Long, h: Long, l: Long, c: Long, v: Long) {
            jdbc.update(
                """
                INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                stockId,
                java.sql.Timestamp.from(day.atStartOfDay(KST).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant()),
                BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c), v,
            )
        }

        insertMinuteCandle(day1, 9, 0, 50, 55, 48, 52, 10)
        insertMinuteCandle(day1, 9, 1, 52, 60, 51, 58, 20)
        insertMinuteCandle(day2, 9, 0, 58, 70, 57, 65, 30)

        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM candles_1d", Int::class.java),
        ).isEqualTo(0)

        CandleAggregator(jdbc).backfillOnStartup()

        val rows = jdbc.queryForList(
            "SELECT open, high, low, close, volume FROM candles_1d WHERE stock_id = ? ORDER BY candle_time ASC",
            stockId,
        )
        assertThat(rows).hasSize(2)

        val d1 = rows[0]
        assertThat((d1["open"] as BigDecimal).toInt()).isEqualTo(50)
        assertThat((d1["high"] as BigDecimal).toInt()).isEqualTo(60)
        assertThat((d1["low"] as BigDecimal).toInt()).isEqualTo(48)
        assertThat((d1["close"] as BigDecimal).toInt()).isEqualTo(58)
        assertThat((d1["volume"] as Number).toLong()).isEqualTo(30L)

        val d2 = rows[1]
        assertThat((d2["open"] as BigDecimal).toInt()).isEqualTo(58)
        assertThat((d2["close"] as BigDecimal).toInt()).isEqualTo(65)
        assertThat((d2["volume"] as Number).toLong()).isEqualTo(30L)
    }

    @Test
    fun `backfillOnStartup still backfills history even when a live flush already wrote today's row first`() {
        // 재현 대상: MarketTickScheduler(fixedDelay=1s)가 기동 직후부터 tick을 흘리므로,
        // backfillOnStartup()의 initialDelay(5s)보다 먼저 오늘자 flush()가 candles_1d에
        // 행을 하나 만들 수 있다. count(*) > 0 가드였다면 이 한 행만으로 "이미 채워짐"으로
        // 오판해 과거 이력을 영원히 스킵했을 시나리오.
        val aggregator = CandleAggregator(jdbc)
        val stockId = insertStock("RACE1")
        val historicalDay = LocalDate.of(2026, 8, 20)
        val today = LocalDate.now(KST)

        jdbc.update(
            """
            INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            stockId,
            java.sql.Timestamp.from(historicalDay.atStartOfDay(KST).plusHours(9).toInstant()),
            BigDecimal(50), BigDecimal(55), BigDecimal(48), BigDecimal(52), 10L,
        )

        // 오늘자 실시간 flush가 먼저 도착 — candles_1d에 오늘 행 1개가 이미 생긴 상태를 재현
        aggregator.onTick(tick(stockId, today, 9, 0, 200, 5))
        aggregator.flushAll()
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM candles_1d WHERE stock_id = ?", Int::class.java, stockId),
        ).isEqualTo(1)

        aggregator.backfillOnStartup()

        val rows = jdbc.queryForList(
            "SELECT candle_time, close FROM candles_1d WHERE stock_id = ? ORDER BY candle_time ASC",
            stockId,
        )
        assertThat(rows).hasSize(2)
        assertThat((rows[0]["close"] as BigDecimal).toInt()).isEqualTo(52) // 백필된 과거 행
        assertThat((rows[1]["close"] as BigDecimal).toInt()).isEqualTo(200) // 실시간으로 유지되던 오늘 행
    }
}
