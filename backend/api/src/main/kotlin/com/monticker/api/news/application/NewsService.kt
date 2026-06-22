package com.monticker.api.news.application

import com.monticker.api.news.domain.NewsArticle
import com.monticker.api.news.infrastructure.NewsRepository
import org.springframework.stereotype.Service

@Service
class NewsService(private val newsRepository: NewsRepository) {

    fun getNews(stockId: Long, limit: Int = 20): List<NewsArticle> =
        newsRepository.findByStockId(stockId, limit)
}
