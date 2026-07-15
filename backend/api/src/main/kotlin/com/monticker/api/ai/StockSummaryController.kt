package com.monticker.api.ai

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.stock.application.StockService
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
class StockSummaryController(
    private val stockSummaryService: StockSummaryService,
    private val stockService: StockService,
    private val esOps: ElasticsearchOperations,
) {
    /**
     * 종목 AI 요약. ES에 1시간 이내 캐시가 있으면 Claude API를 재호출하지 않는다.
     */
    @GetMapping("/api/stocks/{stockId}/summary")
    @RateLimited(limit = 30, windowSec = 3600, keyPrefix = "ai.summary")
    fun getSummary(@PathVariable stockId: Long): ResponseEntity<SummaryResponse> {
        return try {
            val stock = stockService.getById(stockId)
            val summary = stockSummaryService.getSummary(stockId, stock.name)
            ResponseEntity.ok(SummaryResponse(stockId = stockId, summary = summary))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * 생성된 AI 요약을 키워드로 검색한다.
     * 여러 종목의 최신 요약을 한 번에 탐색할 때 사용한다.
     *
     * GET /api/summaries/search?query=반도체
     * GET /api/summaries/search?query=금리&limit=10
     */
    @GetMapping("/api/summaries/search")
    fun searchSummaries(
        @RequestParam query: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): ResponseEntity<List<SummaryResponse>> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        return try {
            val nativeQuery = NativeQuery.builder()
                .withQuery { q ->
                    q.bool { b ->
                        b.must { m ->
                            m.match { mm ->
                                mm.field("summary").query(query)
                                    .analyzer("nori_analyzer")
                            }
                        }
                        b
                    }
                }
                .withMaxResults(limit.coerceIn(1, 50))
                .build()

            val hits = esOps.search(nativeQuery, SummaryDocument::class.java)
            val results = hits.map { hit ->
                SummaryResponse(
                    stockId     = hit.content.stockId,
                    stockName   = hit.content.stockName,
                    summary     = hit.content.summary,
                    generatedAt = hit.content.generatedAt,
                    score       = hit.score,
                )
            }.toList()
            ResponseEntity.ok(results)
        } catch (e: Exception) {
            ResponseEntity.ok(emptyList())
        }
    }
}

data class SummaryResponse(
    val stockId: Long,
    val stockName: String? = null,
    val summary: String,
    val generatedAt: Instant? = null,
    val score: Float? = null,
)
