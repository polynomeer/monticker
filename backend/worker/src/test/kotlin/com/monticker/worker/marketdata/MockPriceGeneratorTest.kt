package com.monticker.worker.marketdata

import com.monticker.worker.kis.KisCoverageProvider
import com.monticker.worker.toss.TossCoverageProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

class MockPriceGeneratorTest {

    // ingestion.source=internal — KisCoverageProvider/TossCoverageProvider의 자체 쿼리는
    // short-circuit되어 실행되지 않으므로 jdbc mock을 그냥 넘겨도 안전하다.
    private val noKisCoverage = KisCoverageProvider(mockk(), "internal")
    private val noTossCoverage = TossCoverageProvider(mockk(), "internal", noKisCoverage)

    private val jdbc = mockk<JdbcTemplate> {
        // @PostConstruct loadStocks() 가 호출하는 DB 쿼리를 스텁
        every { query(any<String>(), any<RowMapper<Any>>()) } returns emptyList<Any>()
    }
    private val generator = MockPriceGenerator(jdbc, noKisCoverage, noTossCoverage)

    @Test
    fun `DB에 종목 없으면 generate는 빈 리스트를 반환한다`() {
        // loadStocks()는 @PostConstruct 이므로 직접 인스턴스화 시 자동 호출되지 않는다.
        // DB mock이 emptyList를 반환하므로 stocks 내부 목록이 비어 있고, generate()도 비어 있어야 한다.
        val ticks = generator.generate()
        assertThat(ticks).isEmpty()
    }

    @Test
    fun `DB에 로드된 종목마다 실제 종목 메타데이터로 양수 가격의 틱을 생성한다`() {
        // loadStocks()의 RowMapper<StockMeta>가 반환하는 실제 매핑 결과를 모사한다.
        // StockMeta는 파일 전용(private) 클래스라 테스트에서 직접 만들 수 없으므로,
        // JdbcTemplate.query에 전달되는 RowMapper 람다를 실제로 호출시켜 프로덕션 매핑 로직을 그대로 태운다.
        val loadingJdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val rs = mockk<ResultSet>()
                every { rs.getLong("id") } returns 1L
                every { rs.getString("symbol") } returns "005930"
                every { rs.getString("market") } returns "KOSPI"
                listOf(mapper.mapRow(rs, 0))
            }
        }
        val loadedGenerator = MockPriceGenerator(loadingJdbc, noKisCoverage, noTossCoverage)
        loadedGenerator.loadStocks()

        val ticks = loadedGenerator.generate()

        assertThat(ticks).hasSize(1)
        val tick = ticks.first()
        assertThat(tick.stockId).isEqualTo(1L)
        assertThat(tick.symbol).isEqualTo("005930")
        assertThat(tick.market).isEqualTo("KOSPI")
        assertThat(tick.price).isPositive()
    }

    @Test
    fun `KIS가 실시간으로 커버하는 종목은 Mock 생성에서 제외한다`() {
        // ingestion.source=kis 이고 KisCoverageProvider가 stockId=1을 커버 대상으로 계산했다면,
        // 나머지 로직이 동일해도 MockPriceGenerator는 그 종목만 정확히 건너뛰어야 한다(ADR-030).
        val kisJdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val rs = mockk<ResultSet>()
                every { rs.getLong("id") } returns 1L
                every { rs.getString("symbol") } returns "005930"
                every { rs.getString("market") } returns "KOSPI"
                listOf(mapper.mapRow(rs, 0))
            }
        }
        val kisCoverage = KisCoverageProvider(kisJdbc, "kis", "k", "s")

        val loadingJdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val covered = mockk<ResultSet> {
                    every { getLong("id") } returns 1L
                    every { getString("symbol") } returns "005930"
                    every { getString("market") } returns "KOSPI"
                }
                val uncovered = mockk<ResultSet> {
                    every { getLong("id") } returns 2L
                    every { getString("symbol") } returns "000660"
                    every { getString("market") } returns "KOSPI"
                }
                listOf(mapper.mapRow(covered, 0), mapper.mapRow(uncovered, 1))
            }
        }
        val loadedGenerator = MockPriceGenerator(loadingJdbc, kisCoverage, noTossCoverage)
        loadedGenerator.loadStocks()

        val ticks = loadedGenerator.generate()

        assertThat(ticks).hasSize(1)
        assertThat(ticks.first().stockId).isEqualTo(2L)
    }

    @Test
    fun `Toss가 실시간으로 커버하는 종목은 Mock 생성에서 제외한다`() {
        // ingestion.source=toss이고 TossCoverageProvider가 stockId=3(미국)을 커버 대상으로
        // 계산했다면, MockPriceGenerator는 그 종목만 정확히 건너뛰어야 한다(ADR-031).
        val tossJdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val rs = mockk<ResultSet> {
                    every { getLong("id") } returns 3L
                    every { getString("symbol") } returns "AAPL"
                    every { getString("market") } returns "NASDAQ"
                }
                listOf(mapper.mapRow(rs, 0))
            }
        }
        val tossCoverage = TossCoverageProvider(tossJdbc, "toss", noKisCoverage, "k", "s")

        val loadingJdbc = mockk<JdbcTemplate> {
            every { query(any<String>(), any<RowMapper<Any>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val mapper = secondArg<RowMapper<Any>>()
                val covered = mockk<ResultSet> {
                    every { getLong("id") } returns 3L
                    every { getString("symbol") } returns "AAPL"
                    every { getString("market") } returns "NASDAQ"
                }
                val uncovered = mockk<ResultSet> {
                    every { getLong("id") } returns 4L
                    every { getString("symbol") } returns "MSFT"
                    every { getString("market") } returns "NASDAQ"
                }
                listOf(mapper.mapRow(covered, 0), mapper.mapRow(uncovered, 1))
            }
        }
        val loadedGenerator = MockPriceGenerator(loadingJdbc, noKisCoverage, tossCoverage)
        loadedGenerator.loadStocks()

        val ticks = loadedGenerator.generate()

        assertThat(ticks).hasSize(1)
        assertThat(ticks.first().stockId).isEqualTo(4L)
    }

    private fun samsungJdbc(lastClose: String?, market: String = "KOSPI") = mockk<JdbcTemplate> {
        every { query(any<String>(), any<RowMapper<Any>>()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet>()
            every { rs.getLong("id") } returns 1L
            every { rs.getString("symbol") } returns "005930"
            every { rs.getString("market") } returns market
            listOf(mapper.mapRow(rs, 0))
        }
        every { queryForList(MockPriceGenerator.LAST_PRICE_SQL) } returns
            if (lastClose == null) emptyList() else listOf(mapOf("stock_id" to 1L, "close" to java.math.BigDecimal(lastClose)))
    }

    // 로컬 점검(2026-10-09) — 재기동마다 seed(71,000)에서 다시 시작해 40,000원대 봉 다음 틱이 +77%였다
    @Test
    fun `기동 시 DB의 마지막 시세에서 이어서 시작한다`() {
        val g = MockPriceGenerator(samsungJdbc("40450.0000"), noKisCoverage, noTossCoverage).apply { loadStocks() }

        val price = g.generate().single().price.toDouble()

        assertThat(price).isBetween(40450 * 0.99, 40450 * 1.01)
    }

    @Test
    fun `마지막 시세를 모르면 seed 기준가로 시작한다`() {
        val g = MockPriceGenerator(samsungJdbc(null), noKisCoverage, noTossCoverage).apply { loadStocks() }

        assertThat(g.generate().single().price.toDouble()).isBetween(71_000 * 0.99, 71_000 * 1.01)
    }

    @Test
    fun `평균 회귀로 하루치 틱이 쌓여도 기준가에서 크게 벗어나지 않는다`() {
        // 시장 코드가 KRX·미국이 아니면 MarketSchedule이 항상 OPEN·배율 1.0이다 — 요일·시각과 무관하게 최대 변동성으로 본다
        val g = MockPriceGenerator(samsungJdbc("50000", market = "TEST"), noKisCoverage, noTossCoverage).apply { loadStocks() }

        // 정규장 6시간 30분 × 1초 틱. 회귀가 없으면 이 정도 틱에 표준편차가 40%를 넘는다.
        val prices = (1..23_400).map { g.generate().single().price.toDouble() }

        assertThat(prices.maxOf { kotlin.math.abs(it / 50_000 - 1) }).isLessThan(0.15)
    }
}
