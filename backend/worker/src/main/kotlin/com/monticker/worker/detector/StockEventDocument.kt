package com.monticker.worker.detector

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.*
import java.time.Instant

// api 모듈의 StockEventDocument와 동일한 인덱스를 공유한다.
@Document(indexName = "stock_events")
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

    @Field(type = FieldType.Keyword)
    val sourceType: String? = null,
)
