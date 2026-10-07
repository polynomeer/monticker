package com.monticker.worker.marketindex

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

class MarketIndexCollectorTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val txManager = mockk<PlatformTransactionManager>(relaxed = true)

    // 2026-10-06(화) 10:00 KST — 10/5(월)는 개천절 대체공휴일이라 피한다(ADR-086)
    private val mondayMorning = Instant.parse("2026-10-06T01:00:00Z")

    private fun stubStoredValues(values: List<Pair<MarketIndexCode?, BigDecimal>>) {
        every { jdbc.query(match<String> { it.startsWith("SELECT code, value") }, any<RowMapper<Any>>()) } returns values
    }

    private fun stubDailyCount(count: Int) {
        every { jdbc.queryForObject(match<String> { it.contains("COUNT(*) FROM market_index_daily") }, Int::class.java, *anyVararg()) } returns count
    }

    @Test
    fun `mock provider moves only while the KR session is open and starts from base when empty`() {
        val closed = MockMarketIndexProvider(Random(1), isKrSessionOpen = { false })
        val prev = mapOf(MarketIndexCode.KOSPI to BigDecimal("2500.00"))

        val ticks = closed.fetch(prev, mondayMorning)

        // KOSPI는 값이 있고 장이 닫혀 있어 움직이지 않는다. 값이 없는 KOSDAQ·USDKRW는 시작값으로 채운다.
        assertThat(ticks.map { it.code }).containsExactlyInAnyOrder(MarketIndexCode.KOSDAQ, MarketIndexCode.USDKRW)
        assertThat(ticks.first { it.code == MarketIndexCode.KOSDAQ }.value).isEqualByComparingTo("850.00")

        val open = MockMarketIndexProvider(Random(1), isKrSessionOpen = { true })
        val moved = open.fetch(prev, mondayMorning).first { it.code == MarketIndexCode.KOSPI }
        assertThat(moved.value.toDouble()).isBetween(2500 * 0.9, 2500 * 1.1)
    }

    @Test
    fun `mock history covers only weekdays, oldest first, ending before the given day`() {
        val history = MockMarketIndexProvider(Random(2)).history(MarketIndexCode.KOSPI, LocalDate.of(2026, 10, 5), 10)

        assertThat(history).hasSize(10)
        assertThat(history.map { it.first }).isSorted
        assertThat(history.last().first).isEqualTo(LocalDate.of(2026, 10, 2)) // 금요일
        assertThat(history.map { it.first.dayOfWeek }).doesNotContain(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        assertThat(history.last().second).isEqualByComparingTo("2600.00")
    }

    @Test
    fun `stores quote with previous close and today's daily row`() {
        stubDailyCount(10)
        stubStoredValues(listOf(MarketIndexCode.KOSPI to BigDecimal("2600.00")))
        every {
            jdbc.query(match<String> { it.contains("trade_date < ?") }, any<RowMapper<BigDecimal>>(), *anyVararg())
        } returns listOf(BigDecimal("2590.00"))
        val provider = mockk<MarketIndexProvider> {
            every { source } returns "MOCK"
            every { isMock } returns true
            every { fetch(any(), any()) } returns listOf(MarketIndexTick(MarketIndexCode.KOSPI, BigDecimal("2610.50"), mondayMorning))
        }

        MarketIndexCollector(provider, jdbc, txManager).collectOnce(mondayMorning)

        verify {
            jdbc.update(match<String> { it.contains("INSERT INTO market_index_quotes") },
                "KOSPI", "코스피", BigDecimal("2610.50"), BigDecimal("2590.00"), any(), "MOCK", true)
        }
        verify {
            jdbc.update(match<String> { it.contains("INSERT INTO market_index_daily") },
                "KOSPI", java.sql.Date.valueOf(LocalDate.of(2026, 10, 6)), BigDecimal("2610.50"), true)
        }
    }

    @Test
    fun `KRX holiday updates the quote but writes no daily row`() {
        stubDailyCount(10)
        stubStoredValues(listOf(MarketIndexCode.KOSPI to BigDecimal("2600.00")))
        every { jdbc.query(match<String> { it.contains("trade_date < ?") }, any<RowMapper<BigDecimal>>(), *anyVararg()) } returns emptyList()
        val holidayMorning = Instant.parse("2026-10-05T01:00:00Z")   // 개천절 대체공휴일(월)
        val provider = mockk<MarketIndexProvider> {
            every { source } returns "MOCK"
            every { isMock } returns true
            every { fetch(any(), any()) } returns listOf(MarketIndexTick(MarketIndexCode.KOSPI, BigDecimal("2610.50"), holidayMorning))
        }
        val collector = MarketIndexCollector(provider, jdbc, txManager).apply {
            isKrBusinessDay = { it != LocalDate.of(2026, 10, 5) && it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        }

        collector.collectOnce(holidayMorning)

        verify { jdbc.update(match<String> { it.contains("INSERT INTO market_index_quotes") }, *anyVararg()) }
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT INTO market_index_daily") }, *anyVararg()) }
    }

    @Test
    fun `mock history skips KRX holidays`() {
        val history = MockMarketIndexProvider(Random(2), { false }) { it != LocalDate.of(2026, 10, 2) && it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
            .history(MarketIndexCode.KOSPI, LocalDate.of(2026, 10, 5), 3)
        assertThat(history.map { it.first }).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1))
    }

    @Test
    fun `no ticks means no writes`() {
        stubDailyCount(10)
        stubStoredValues(emptyList())
        val provider = mockk<MarketIndexProvider> {
            every { source } returns "MOCK"
            every { isMock } returns true
            every { fetch(any(), any()) } returns emptyList()
        }

        MarketIndexCollector(provider, jdbc, txManager).collectOnce(mondayMorning)

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("INSERT") }, *anyVararg()) }
    }

    @Test
    fun `backfills an empty daily table from provider history once`() {
        stubDailyCount(0)
        stubStoredValues(emptyList())
        val provider = mockk<MarketIndexProvider> {
            every { source } returns "MOCK"
            every { isMock } returns true
            every { fetch(any(), any()) } returns emptyList()
            every { history(any(), any(), any()) } returns listOf(LocalDate.of(2026, 10, 2) to BigDecimal("2600"))
        }
        val collector = MarketIndexCollector(provider, jdbc, txManager)

        collector.collectOnce(mondayMorning)
        collector.collectOnce(mondayMorning)

        verify(exactly = MarketIndexCode.entries.size) { provider.history(any(), LocalDate.of(2026, 10, 6), MarketIndexCollector.BACKFILL_TRADING_DAYS) }
    }

    @Test
    fun `unknown provider setting fails startup instead of silently using mock`() {
        assertThatThrownBy { MarketIndexProviderConfig().marketIndexProvider("kis") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(MarketIndexProviderConfig().marketIndexProvider("mock").isMock).isTrue()
    }
}
