package com.monticker.worker.news

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.DateFormat
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.Instant

// api 모듈의 NewsDocument와 동일한 인덱스를 공유한다.
@Document(indexName = "news_articles")
@Setting(settingPath = "elasticsearch/news-index-settings.json")
data class NewsDocument(
    @Id val id: String,

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
)
