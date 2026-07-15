package com.monticker.api.event.api

import com.monticker.api.event.application.EventSearchResult
import com.monticker.api.event.domain.StockEvent
import java.math.BigDecimal
import java.time.Instant

data class StockEventResponse(
    val id: Long,
    val stockId: Long,
    val eventType: String,
    val title: String,
    val description: String?,
    val eventTime: Instant,
    val importanceScore: Int,
    val sentimentScore: BigDecimal?,
    val sourceType: String?,
    val score: Float? = null,
) {
    companion object {
        fun from(e: StockEvent) = StockEventResponse(
            id              = e.id,
            stockId         = e.stockId,
            eventType       = e.eventType.name,
            title           = e.title,
            description     = e.description,
            eventTime       = e.eventTime,
            importanceScore = e.importanceScore,
            sentimentScore  = e.sentimentScore,
            sourceType      = e.sourceType,
        )

        fun from(r: EventSearchResult) = StockEventResponse(
            id              = r.id,
            stockId         = r.stockId,
            eventType       = r.eventType,
            title           = r.title,
            description     = r.description,
            eventTime       = r.eventTime,
            importanceScore = r.importanceScore,
            sentimentScore  = r.sentimentScore?.let { BigDecimal.valueOf(it) },
            sourceType      = r.sourceType,
            score           = r.score,
        )
    }
}
