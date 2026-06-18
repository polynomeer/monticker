package com.monticker.api.stock.application

import com.monticker.api.stock.domain.Stock
import com.monticker.api.stock.infrastructure.StockRepository
import org.springframework.stereotype.Service

@Service
class StockService(
    private val stockRepository: StockRepository,
) {
    fun search(query: String): List<Stock> {
        require(query.length >= 1) { "Query must be at least 1 character" }
        return stockRepository.searchByNameOrSymbol(query)
    }

    fun getById(id: Long): Stock =
        stockRepository.findById(id).orElseThrow {
            NoSuchElementException("Stock not found: $id")
        }
}
