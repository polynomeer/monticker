package com.monticker.api.quant.application

import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 종목 차트 퀀트 시그널 — 볼 수 있는 전략(내 것 + 구독)만 SQL에 들어간다(ADR-035와 같은 기준). */
class StockSignalQueryServiceTest {
    private val repo = mockk<RuleSetRepository>()
    private val jdbc = mockk<NamedParameterJdbcTemplate>()
    private val service = StockSignalQueryService(repo, jdbc)

    @Test
    fun `returns nothing and never queries signals when the user can see no strategy`() {
        every { repo.findAllByUserId(1L) } returns emptyList()
        every { jdbc.query(match<String> { it.contains("strategy_subscriptions") }, any<MapSqlParameterSource>(), any<RowMapper<String>>()) } returns emptyList()

        assertThat(service.signalsForStock(1L, 5L, 30)).isEmpty()
        verify(exactly = 0) { jdbc.query(match<String> { it.contains("FROM quant_signals") }, any<MapSqlParameterSource>(), any<RowMapper<Any>>()) }
    }

    @Test
    fun `queries only owned and subscribed rule sets`() {
        every { repo.findAllByUserId(1L) } returns listOf(RuleSetDocument(id = "own1", userId = 1L, name = "내 전략"))
        every { jdbc.query(match<String> { it.contains("strategy_subscriptions") }, any<MapSqlParameterSource>(), any<RowMapper<String>>()) } returns listOf("sub1")
        every { repo.findAllById(setOf("sub1")) } returns listOf(RuleSetDocument(id = "sub1", userId = 9L, name = "구독 전략"))
        val params = slot<MapSqlParameterSource>()
        every { jdbc.query(match<String> { it.contains("FROM quant_signals") }, capture(params), any<RowMapper<StockSignalResponse>>()) } returns emptyList()

        service.signalsForStock(1L, 5L, 30)

        @Suppress("UNCHECKED_CAST")
        assertThat(params.captured.getValue("ids") as Set<String>).containsExactlyInAnyOrder("own1", "sub1")
        assertThat(params.captured.getValue("stockId")).isEqualTo(5L)
    }
}
