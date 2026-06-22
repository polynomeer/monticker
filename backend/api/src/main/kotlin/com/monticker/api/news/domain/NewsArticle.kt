package com.monticker.api.news.domain

import java.time.Instant

data class NewsArticle(
    val id: Long,
    val stockId: Long?,
    val title: String,
    val description: String?,
    val url: String,
    val source: String?,
    val publishedAt: Instant,
    val sentiment: String?,
)
