package com.monticker.worker.marketdata

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

class MockPriceGeneratorTest {

    private val jdbc = mockk<JdbcTemplate> {
        // @PostConstruct loadStocks() 가 호출하는 DB 쿼리를 스텁
        every { query(any<String>(), any<RowMapper<Any>>()) } returns emptyList<Any>()
    }
    private val generator = MockPriceGenerator(jdbc)

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
        val loadedGenerator = MockPriceGenerator(loadingJdbc)
        loadedGenerator.loadStocks()

        val ticks = loadedGenerator.generate()

        assertThat(ticks).hasSize(1)
        val tick = ticks.first()
        assertThat(tick.stockId).isEqualTo(1L)
        assertThat(tick.symbol).isEqualTo("005930")
        assertThat(tick.market).isEqualTo("KOSPI")
        assertThat(tick.price).isPositive()
    }
}
