package com.monticker.api.ai

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.*
import java.time.Instant

@Document(indexName = "stock_summaries", createIndex = false)
@Setting(settingPath = "elasticsearch/summary-index-settings.json")
data class SummaryDocument(
    @Id val id: String,            // stockId를 문자열로 사용

    @Field(type = FieldType.Long)
    val stockId: Long,

    @Field(type = FieldType.Keyword)
    val stockName: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val summary: String,

    @Field(type = FieldType.Date, format = [DateFormat.epoch_millis])
    val generatedAt: Instant = Instant.now(),
)
