package com.monticker.api.stock.application

import com.monticker.api.common.metrics.SearchMetrics
import com.monticker.api.stock.infrastructure.StockRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations

/**
 * resilience-plan §A6 / P1-2 — ES 폴백은 이미 잘 설계돼 있다. 빠져 있던 건 "폴백했다"는 신호다.
 */
class StockSearchServiceTest {

    private val esOps = mockk<ElasticsearchOperations>()
    private val stockRepository = mockk<StockRepository>()
    private val registry = SimpleMeterRegistry()
    private val service = StockSearchService(esOps, stockRepository, SearchMetrics(registry))

    @Test
    fun `ES 실패 시 DB로 폴백하고 search_fallback_total{index=stocks}를 올린다`() {
        every { esOps.search(any<org.springframework.data.elasticsearch.core.query.Query>(), any<Class<*>>()) } throws RuntimeException("ES down")
        every { stockRepository.searchByNameOrSymbol("삼성") } returns emptyList()

        val result = service.search("삼성")

        assertThat(result).isEmpty()                       // 예외가 새지 않고 DB 결과가 반환됐다
        assertThat(registry.counter("search_fallback_total", "index", "stocks").count()).isEqualTo(1.0)
    }
}
