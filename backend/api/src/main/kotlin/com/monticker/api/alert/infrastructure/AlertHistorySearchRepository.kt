package com.monticker.api.alert.infrastructure

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface AlertHistorySearchRepository : ElasticsearchRepository<AlertHistoryDocument, String>
