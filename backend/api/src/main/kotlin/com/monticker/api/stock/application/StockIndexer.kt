package com.monticker.api.stock.application

import com.monticker.api.stock.infrastructure.StockDocument
import com.monticker.api.stock.infrastructure.StockRepository
import com.monticker.api.stock.infrastructure.StockSearchRepository
import com.monticker.api.common.search.SearchReindexer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * PostgreSQL → Elasticsearch 종목 인덱스 전량 동기화. 종목은 거의 바뀌지 않아 실시간 경로가 없다 —
 * 새 환경에서는 관리자 재색인(`POST /api/admin/search/reindex/stocks`)을 한 번 실행해야 한다 (ADR-042 §5).
 */
@Component
class StockIndexer(
    private val stockRepository: StockRepository,
    private val stockSearchRepository: StockSearchRepository,
) : SearchReindexer {
    override val index = "stocks"
    override val documentClass: Class<*> = StockDocument::class.java

    private val log = LoggerFactory.getLogger(javaClass)

    override fun reindexAll(): Int {
        val docs = stockRepository.findAll().map { StockDocument.from(it) }
        docs.chunked(500).forEach { stockSearchRepository.saveAll(it) }
        return docs.size
    }
}
