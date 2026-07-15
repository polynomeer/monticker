package com.monticker.api.ai

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface SummarySearchRepository : ElasticsearchRepository<SummaryDocument, String>
