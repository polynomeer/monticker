package com.monticker.api.screener.application

import com.monticker.api.common.cache.CacheConfig
import com.monticker.api.common.tracing.Tracing
import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.ScreenerRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScreenerService(private val repo: ScreenerRepository) {

    /**
     * 스크리너 결과를 5초간 캐싱한다.
     * key에 모든 파라미터를 포함해 탭/정렬/페이지 조합별로 독립 캐시 엔트리를 유지한다.
     */
    @Cacheable(
        cacheNames = [CacheConfig.SCREENER],
        key = "#tab + ':' + #market + ':' + #sort + ':' + #limit + ':' + #offset",
    )
    fun getItems(
        tab: String    = "realtime",
        market: String = "all",
        sort: String   = "amount",
        limit: Int     = 20,
        offset: Int    = 0,
    ): ScreenerResult {
        return Tracing.span("screener.getItems", mapOf(
            "screener.tab"    to tab,
            "screener.market" to market,
            "screener.sort"   to sort,
            "screener.offset" to offset,
        )) { span ->
            val effectiveSort = when (tab) {
                "movers" -> if (sort == "fall") "fall" else "rise"
                else     -> sort
            }
            val items = repo.findItems(market, effectiveSort, limit.coerceIn(1, 50), offset)
            val total = repo.count(market)
            span.setAttribute("screener.resultCount", items.size.toLong())
            ScreenerResult(items, total, offset + items.size < total)
        }
    }
}

data class ScreenerResult(
    val items: List<ScreenerItem>,
    val total: Int,
    val hasMore: Boolean,
)
