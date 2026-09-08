package com.monticker.worker.marketdata

import com.monticker.worker.kis.KisCoverageProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

class MockPriceGeneratorTest {

    // ingestion.source=internal — KisCoverageProvider의 자체 쿼리는 short-circuit되어
    // 실행되지 않으므로 jdbc mock을 그냥 넘겨도 안전하다.
    private val noCoverage = KisCoverageProvider(mockk(), "internal")

    private val jdbc = mockk<JdbcTemplate> {
        // @PostConstruct loadStocks() 가 호출하는 DB 쿼리를 스텁
        every { query(any<String>(), any<RowMapper<Any>>()) } returns emptyList<Any>()
    }
    private val generator = MockPriceGenerator(jdbc, noCoverage)

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
        val loadedGenerator = MockPriceGenerator(loadingJdbc, noCoverage)
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
        val kisCoverage = KisCoverageProvider(kisJdbc, "kis")

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
        val loadedGenerator = MockPriceGenerator(loadingJdbc, kisCoverage)
        loadedGenerator.loadStocks()

        val ticks = loadedGenerator.generate()

        assertThat(ticks).hasSize(1)
        assertThat(ticks.first().stockId).isEqualTo(2L)
    }
}
