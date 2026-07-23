package com.monticker.api.watchlist.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.*
import java.math.BigDecimal
import java.time.Instant

@Document(indexName = "watchlist_items", createIndex = false)
@Setting(settingPath = "elasticsearch/watchlist-index-settings.json")
data class WatchlistItemDocument(
    @Id val id: String,                // itemId

    @Field(type = FieldType.Long)
    val userId: Long,

    @Field(type = FieldType.Long)
    val groupId: Long,

    @Field(type = FieldType.Keyword)
    val groupName: String,

    @Field(type = FieldType.Long)
    val stockId: Long,

    @Field(type = FieldType.Keyword)
    val symbol: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val stockName: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val sector: String? = null,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val memo: String? = null,

    @Field(type = FieldType.Double)
    val targetPrice: Double? = null,

    @Field(type = FieldType.Date, format = [DateFormat.epoch_millis])
    val createdAt: Instant = Instant.now(),
)
