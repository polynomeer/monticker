package com.monticker.api.watchlist.infrastructure

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface WatchlistSearchRepository : ElasticsearchRepository<WatchlistItemDocument, String>
