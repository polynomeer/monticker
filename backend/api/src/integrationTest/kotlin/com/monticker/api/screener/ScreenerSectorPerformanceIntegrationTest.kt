package com.monticker.api.screener

import com.monticker.api.screener.infrastructure.ScreenerRepository
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ADR-087 — 섹터 등락률 집계를 실제 Postgres/TimescaleDB에서 검증한다.
 * 종목 등락률은 스크리너와 같은 식(최신 1분봉 종가 vs 직전 일봉 종가), 섹터 값은 동일가중 평균이다.
 *
 * 전 종목을 훑는 쿼리라 다른 테스트가 만든 종목도 결과에 섞인다 — 고유한 섹터 이름으로 내 행만 본다.
 */
class ScreenerSectorPerformanceIntegrationTest : PostgresIntegrationTest() {

    private val repo by lazy { ScreenerRepository(jdbcTemplate) }
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)

    private fun ts(i: Instant) = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

    private fun stock(sector: String?, market: String = "KOSPI", active: Boolean = true): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange, sector, is_active) VALUES (?, '섹터테스트', ?, 'X', ?, ?) RETURNING id",
        Long::class.java, "SP" + UUID.randomUUID().toString().take(8), market, sector, active,
    )!!

    /** 직전 일봉 종가 prev, 최신 1분봉 종가 last */
    private fun prices(stockId: Long, prev: Int, last: Int) {
        val d1 = now.minus(2, ChronoUnit.DAYS)
        val d0 = now.minus(1, ChronoUnit.DAYS)
        for ((t, c) in listOf(d1 to prev, d0 to prev + 1)) {   // OFFSET 1 → d1의 종가가 "직전 일봉"
            jdbcTemplate.update(
                "INSERT INTO candles_1d (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 100, ?)",
                stockId, c, c, c, c, ts(t),
            )
        }
        jdbcTemplate.update(
            "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 10, ?)",
            stockId, last, last, last, last, ts(now.minus(5, ChronoUnit.MINUTES)),
        )
    }

    private fun event(stockId: Long, type: String, at: Instant) {
        jdbcTemplate.update(
            "INSERT INTO stock_events (stock_id, event_type, title, event_time, importance_score) VALUES (?, ?, 't', ?, 5)",
            stockId, type, ts(at),
        )
    }

    @Test
    fun `섹터 등락률은 등락률이 있는 종목의 단순 평균이고 이벤트 수는 구간 안만 센다`() {
        val sector = "섹터-" + UUID.randomUUID().toString().take(8)
        val up = stock(sector).also { prices(it, prev = 100, last = 110) }      // +10%
        val down = stock(sector).also { prices(it, prev = 200, last = 190) }    // -5%
        stock(sector)                                                           // 시세 없음 → 평균에서 빠짐
        val flat = stock(sector).also { prices(it, prev = 50, last = 50) }      // 0%
        stock(sector, market = "NASDAQ").also { prices(it, prev = 10, last = 20) }  // domestic에서 빠짐
        stock(sector, active = false).also { prices(it, prev = 10, last = 1) }      // 비활성 → 빠짐

        val from = now.minus(1, ChronoUnit.HOURS)
        val to = now.plus(1, ChronoUnit.HOURS)
        event(up, "PRICE_SPIKE", now.minus(10, ChronoUnit.MINUTES))
        event(down, "PRICE_DROP", now.minus(20, ChronoUnit.MINUTES))
        event(flat, "NEWS_PUBLISHED", now.minus(3, ChronoUnit.HOURS))   // 구간 전 → 제외

        val row = repo.findSectorPerformance("domestic", from, to).single { it.sector == sector }
        assertThat(row.stockCount).isEqualTo(4)
        assertThat(row.pricedCount).isEqualTo(3)
        assertThat(row.avgChangeRate).isCloseTo((10.0 - 5.0 + 0.0) / 3, within(1e-6))
        assertThat(row.advancers).isEqualTo(1)
        assertThat(row.decliners).isEqualTo(1)
        assertThat(row.unchanged).isEqualTo(1)
        assertThat(row.eventCount).isEqualTo(2)

        // 전체 시장이면 NASDAQ 종목(+100%)도 들어간다
        val all = repo.findSectorPerformance("all", from, to).single { it.sector == sector }
        assertThat(all.stockCount).isEqualTo(5)
        assertThat(all.avgChangeRate).isCloseTo((10.0 - 5.0 + 0.0 + 100.0) / 4, within(1e-6))
    }

    @Test
    fun `시세가 하나도 없는 섹터는 평균이 null 이고 이벤트가 없으면 0 이다`() {
        val sector = "빈섹터-" + UUID.randomUUID().toString().take(8)
        stock(sector)
        val row = repo.findSectorPerformance("all", now.minus(1, ChronoUnit.HOURS), now).single { it.sector == sector }
        assertThat(row.pricedCount).isZero()
        assertThat(row.avgChangeRate).isNull()
        assertThat(row.eventCount).isZero()
    }

    @Test
    fun `섹터가 없거나 빈 문자열인 종목은 결과에 없다`() {
        stock(null).also { prices(it, 10, 11) }
        stock("").also { prices(it, 10, 11) }
        val rows = repo.findSectorPerformance("all", now.minus(1, ChronoUnit.HOURS), now)
        assertThat(rows).noneMatch { it.sector.isBlank() }
    }
}
