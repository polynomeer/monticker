package com.monticker.api.news.infrastructure

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface NewsSearchRepository : ElasticsearchRepository<NewsDocument, String>
