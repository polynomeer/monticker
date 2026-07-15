package com.monticker.worker.alert

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.*
import java.time.Instant

// api 모듈의 AlertHistoryDocument와 동일한 인덱스를 공유한다.
@Document(indexName = "alert_histories")
@Setting(settingPath = "elasticsearch/alert-index-settings.json")
data class AlertHistoryDocument(
    @Id val id: String,

    @Field(type = FieldType.Long)
    val ruleId: Long,

    @Field(type = FieldType.Long)
    val userId: Long,

    @Field(type = FieldType.Long)
    val stockId: Long?,

    @Field(type = FieldType.Keyword)
    val ruleType: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer", searchAnalyzer = "nori_analyzer")
    val message: String,

    @Field(type = FieldType.Keyword)
    val deliveryStatus: String,

    @Field(type = FieldType.Date, format = [DateFormat.epoch_millis])
    val triggeredAt: Instant,
)
