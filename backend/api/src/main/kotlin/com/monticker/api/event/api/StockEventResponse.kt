package com.monticker.api.event.api

import com.monticker.api.event.application.EventMarketContext
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
    /** 이벤트 구간 변동률(%) — EventMarketContextService. /api/events/recent에서만 채운다, 없으면 null */
    val windowChangePct: Double? = null,
    /** 이벤트 직후 5분 평균 거래량 ÷ 직전 60분 평균 — 위와 같음 */
    val volumeMultiple: Double? = null,
) {
    companion object {
        fun from(e: StockEvent, ctx: EventMarketContext?) = from(e).copy(
            windowChangePct = ctx?.windowChangePct,
            volumeMultiple  = ctx?.volumeMultiple,
        )

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
