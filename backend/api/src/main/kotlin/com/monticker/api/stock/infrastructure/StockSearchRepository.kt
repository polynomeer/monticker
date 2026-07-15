package com.monticker.api.stock.infrastructure

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface StockSearchRepository : ElasticsearchRepository<StockDocument, String>
