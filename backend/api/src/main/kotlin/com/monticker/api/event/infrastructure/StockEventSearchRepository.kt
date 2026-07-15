package com.monticker.api.event.infrastructure

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface StockEventSearchRepository : ElasticsearchRepository<StockEventDocument, String>
