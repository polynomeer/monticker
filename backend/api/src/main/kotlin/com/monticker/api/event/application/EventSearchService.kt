package com.monticker.api.event.application

import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.StockEventDocument
import com.monticker.api.event.infrastructure.StockEventSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.SearchHit
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class EventSearchService(
    private val esOps: ElasticsearchOperations,
    private val eventRepository: com.monticker.api.event.infrastructure.StockEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 종목별 이벤트 타임라인 — 키워드 검색 + 기간/타입/점수 필터.
     */
    fun searchByStock(
        stockId: Long,
        query: String? = null,
        eventTypes: List<String> = emptyList(),
        minScore: Int = 0,
        from: Instant,
        to: Instant,
        limit: Int = 50,
    ): List<EventSearchResult> {
        if (query.isNullOrBlank() && eventTypes.isEmpty() && minScore == 0) {
            return eventRepository.findByStockIdAndTimeRange(stockId, from, to)
                .take(limit).map { EventSearchResult.from(it) }
        }
        return try {
            searchFromEs(stockId = stockId, query = query, eventTypes = eventTypes, minScore = minScore, from = from, to = to, limit = limit)
        } catch (e: Exception) {
            log.warn("ES event search failed, falling back to DB: {}", e.message)
            eventRepository.findByStockIdAndTimeRange(stockId, from, to)
                .take(limit).map { EventSearchResult.from(it) }
        }
    }

    /**
     * 전 종목 크로스 이벤트 검색 — 뉴스/공시/가격 이벤트 통합 탐색.
     */
    fun searchAll(
        query: String,
        eventTypes: List<String> = emptyList(),
        minScore: Int = 0,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 50,
    ): List<EventSearchResult> {
        return try {
            searchFromEs(stockId = null, query = query, eventTypes = eventTypes, minScore = minScore, from = from, to = to, limit = limit)
        } catch (e: Exception) {
            log.warn("ES global event search failed: {}", e.message)
            emptyList()
        }
    }

    // ── ES 쿼리 빌더 ─────────────────────────────────────────────────────────

    private fun searchFromEs(
        stockId: Long?,
        query: String?,
        eventTypes: List<String>,
        minScore: Int,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<EventSearchResult> {
        val nativeQuery = NativeQuery.builder()
            .withQuery { q ->
                q.bool { b ->
                    // 키워드 검색
                    if (!query.isNullOrBlank()) {
                        b.must { m ->
                            m.multiMatch { mm ->
                                mm.query(query)
                                    .fields("title^3", "description^1")
                                    .analyzer("nori_analyzer")
                            }
                        }
                    }
                    // 종목 필터
                    if (stockId != null) {
                        b.filter { f -> f.term { t -> t.field("stockId").value(stockId) } }
                    }
                    // 이벤트 타입 필터
                    if (eventTypes.isNotEmpty()) {
                        b.filter { f ->
                            f.terms { t ->
                                t.field("eventType").terms { tv ->
                                    tv.value(eventTypes.map { co.elastic.clients.elasticsearch._types.FieldValue.of(it) })
                                }
                            }
                        }
                    }
                    // 중요도 점수 필터
                    if (minScore > 0) {
                        b.filter { f ->
                            f.range { r ->
                                r.number { n -> n.field("importanceScore").gte(minScore.toDouble()) }
                            }
                        }
                    }
                    // 날짜 범위 필터
                    if (from != null || to != null) {
                        b.filter { f ->
                            f.range { r ->
                                r.date { d ->
                                    var dr = d.field("eventTime")
                                    if (from != null) dr = dr.gte(from.toEpochMilli().toString())
                                    if (to != null)   dr = dr.lte(to.toEpochMilli().toString())
                                    dr
                                }
                            }
                        }
                    }
                    b
                }
            }
            .withMaxResults(limit)
            .build()

        val hits = esOps.search(nativeQuery, StockEventDocument::class.java)
        return hits.map { EventSearchResult.from(it) }.toList()
    }
}

// ── 결과 DTO ─────────────────────────────────────────────────────────────────

data class EventSearchResult(
    val id: Long,
    val stockId: Long,
    val eventType: String,
    val title: String,
    val description: String?,
    val eventTime: Instant,
    val importanceScore: Int,
    val sentimentScore: Double?,
    val sourceType: String?,
    val score: Float?,
) {
    companion object {
        fun from(e: StockEvent) = EventSearchResult(
            id              = e.id,
            stockId         = e.stockId,
            eventType       = e.eventType.name,
            title           = e.title,
            description     = e.description,
            eventTime       = e.eventTime,
            importanceScore = e.importanceScore,
            sentimentScore  = e.sentimentScore?.toDouble(),
            sourceType      = e.sourceType,
            score           = null,
        )

        fun from(hit: SearchHit<StockEventDocument>) = hit.content.let { doc ->
            EventSearchResult(
                id              = doc.id.toLong(),
                stockId         = doc.stockId,
                eventType       = doc.eventType,
                title           = doc.title,
                description     = doc.description,
                eventTime       = doc.eventTime,
                importanceScore = doc.importanceScore,
                sentimentScore  = doc.sentimentScore,
                sourceType      = doc.sourceType,
                score           = hit.score,
            )
        }
    }
}
