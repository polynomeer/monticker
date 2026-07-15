package com.monticker.api.stock.application

import com.monticker.api.stock.infrastructure.StockDocument
import com.monticker.api.stock.infrastructure.StockRepository
import com.monticker.api.stock.infrastructure.StockSearchRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 앱 기동 시 PostgreSQL → Elasticsearch 종목 인덱스를 동기화한다.
 * 종목 데이터는 거의 변경되지 않으므로 매 기동 시 전체 upsert로 충분.
 */
@Component
class StockIndexer(
    private val stockRepository: StockRepository,
    private val stockSearchRepository: StockSearchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun indexAll() {
        try {
            val stocks = stockRepository.findAll()
            val docs = stocks.map { StockDocument.from(it) }
            stockSearchRepository.saveAll(docs)
            log.info("Elasticsearch stock index synced: {} documents", docs.size)
        } catch (e: Exception) {
            // ES가 없는 환경(기본 모드)에서 기동 실패를 막는다
            log.warn("Elasticsearch stock indexing skipped: {}", e.message)
        }
    }
}
