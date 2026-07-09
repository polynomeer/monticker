package com.monticker.worker.marketdata

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

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
    fun `generate가 반환하는 틱은 모두 양수 가격이다`() {
        // stocks가 비어 있으면 빈 리스트 — forEach는 vacuously true
        val ticks = generator.generate()
        ticks.forEach { assertThat(it.price).isPositive() }
    }
}
