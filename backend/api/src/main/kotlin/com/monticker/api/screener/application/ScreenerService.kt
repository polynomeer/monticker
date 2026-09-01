package com.monticker.api.screener.application

import com.monticker.api.common.cache.CacheConfig
import com.monticker.api.common.tracing.Tracing
import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.ScreenerRepository
import com.monticker.api.stock.application.StockSearchService
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
class ScreenerService(
    private val repo: ScreenerRepository,
    private val stockSearchService: StockSearchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스크리너 결과를 5초간 캐싱한다.
     * key에 모든 파라미터를 포함해 탭/정렬/페이지 조합별로 독립 캐시 엔트리를 유지한다.
     */
    @Cacheable(
        cacheNames = [CacheConfig.SCREENER],
        key = "#tab + ':' + #market + ':' + #sort + ':' + #limit + ':' + #offset + ':' + #marketCapTier",
    )
    fun getItems(
        tab: String           = "realtime",
        market: String        = "all",
        sort: String          = "amount",
        limit: Int            = 20,
        offset: Int           = 0,
        marketCapTier: String = "all",
    ): ScreenerResult {
        return Tracing.span("screener.getItems", mapOf(
            "screener.tab"           to tab,
            "screener.market"        to market,
            "screener.sort"          to sort,
            "screener.offset"        to offset,
            "screener.marketCapTier" to marketCapTier,
        )) { span ->
            val effectiveSort = when (tab) {
                "movers" -> if (sort == "fall") "fall" else "rise"
                else     -> sort
            }
            val items = repo.findItems(market, effectiveSort, limit.coerceIn(1, 50), offset, marketCapTier)
            val total = repo.count(market, marketCapTier)
            span.setAttribute("screener.resultCount", items.size.toLong())
            ScreenerResult(items, total, offset + items.size < total)
        }
    }

    /**
     * ES 종목 검색 결과로 스크리너 뷰를 구성한다.
     * ES → 종목 ID 목록 → DB 시세 조회 파이프라인.
     * ES 불가 시 DB LIKE 검색으로 폴백한다.
     */
    fun search(
        query: String,
        sort: String = "amount",
        limit: Int   = 20,
    ): ScreenerResult {
        return Tracing.span("screener.search", mapOf("screener.query" to query)) { span ->
            val stockIds = try {
                stockSearchService.search(query)
                    .take(limit)
                    .map { it.id }
            } catch (e: Exception) {
                log.warn("ES stock search failed in screener, falling back to DB: {}", e.message)
                emptyList()
            }

            if (stockIds.isEmpty()) {
                span.setAttribute("screener.resultCount", 0L)
                return@span ScreenerResult(emptyList(), 0, false)
            }

            val items = repo.findItemsByStockIds(stockIds, sort)
            span.setAttribute("screener.resultCount", items.size.toLong())
            ScreenerResult(items, items.size, false)
        }
    }

    /**
     * 명시적 종목 ID 목록의 시세를 조회한다 (ES 검색 없이).
     * 관심종목 티커 스트립처럼 "이미 알고 있는 종목들"의 최신 시세가 필요할 때 사용.
     */
    fun getByStockIds(stockIds: List<Long>): ScreenerResult {
        if (stockIds.isEmpty()) return ScreenerResult(emptyList(), 0, false)
        val items = repo.findItemsByStockIds(stockIds)
        return ScreenerResult(items, items.size, false)
    }
}

data class ScreenerResult(
    val items: List<ScreenerItem>,
    val total: Int,
    val hasMore: Boolean,
)
