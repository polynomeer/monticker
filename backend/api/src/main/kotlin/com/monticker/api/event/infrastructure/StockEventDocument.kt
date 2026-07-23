package com.monticker.api.event.infrastructure

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.*
import java.math.BigDecimal
import java.time.Instant

@Document(indexName = "stock_events", createIndex = false)
@Setting(settingPath = "elasticsearch/event-index-settings.json")
data class StockEventDocument(
    @Id val id: String,

    @Field(type = FieldType.Long)
    val stockId: Long,

    @Field(type = FieldType.Keyword)
    val eventType: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val title: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val description: String? = null,

    @Field(type = FieldType.Date, format = [DateFormat.epoch_millis])
    val eventTime: Instant,

    @Field(type = FieldType.Integer)
    val importanceScore: Int = 0,

    @Field(type = FieldType.Double)
    val sentimentScore: Double? = null,

    @Field(type = FieldType.Keyword)
    val sourceType: String? = null,
) {
    companion object {
        fun from(e: com.monticker.api.event.domain.StockEvent) = StockEventDocument(
            id             = e.id.toString(),
            stockId        = e.stockId,
            eventType      = e.eventType.name,
            title          = e.title,
            description    = e.description,
            eventTime      = e.eventTime,
            importanceScore = e.importanceScore,
            sentimentScore = e.sentimentScore?.toDouble(),
            sourceType     = e.sourceType,
        )
    }
}
