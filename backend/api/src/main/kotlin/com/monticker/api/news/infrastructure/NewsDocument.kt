package com.monticker.api.news.infrastructure

import com.monticker.api.news.domain.NewsArticle
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.DateFormat
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.Instant

@Document(indexName = "news_articles", createIndex = false)
@Setting(settingPath = "elasticsearch/news-index-settings.json")
data class NewsDocument(
    @Id
    val id: String,              // news_articles.id

    @Field(type = FieldType.Long)
    val stockId: Long?,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val title: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val description: String?,

    @Field(type = FieldType.Keyword)
    val url: String,

    @Field(type = FieldType.Keyword)
    val source: String?,

    @Field(type = FieldType.Date, format = [DateFormat.epoch_millis])
    val publishedAt: Instant,

    @Field(type = FieldType.Keyword)
    val sentiment: String?,
) {
    companion object {
        fun from(article: NewsArticle) = NewsDocument(
            id          = article.id.toString(),
            stockId     = article.stockId,
            title       = article.title,
            description = article.description,
            url         = article.url,
            source      = article.source,
            publishedAt = article.publishedAt,
            sentiment   = article.sentiment,
        )
    }
}
