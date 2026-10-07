package com.monticker.api.screener.application

import com.monticker.api.common.metrics.SearchMetrics
import com.monticker.api.common.cache.CacheConfig
import com.monticker.api.common.tracing.Tracing
import com.monticker.api.quant.application.QuantSignalFeedService
import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.ScreenerRepository
import com.monticker.api.screener.infrastructure.SectorCount
import com.monticker.api.screener.infrastructure.SectorPerformance
import com.monticker.api.stock.application.StockSearchService
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 퀀트 시그널 필터를 비로그인으로 요청했을 때 */
class ScreenerLoginRequiredException(message: String) : IllegalArgumentException(message)

@Service
class ScreenerService(
    private val repo: ScreenerRepository,
    private val stockSearchService: StockSearchService,
    private val searchMetrics: SearchMetrics,
    private val quantSignalFeedService: QuantSignalFeedService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")

        /** "오늘" = KST 자정부터. 이벤트 필터·오늘 이벤트 열이 같은 기준을 쓴다 */
        fun todayStart(now: Instant = Instant.now()): Instant =
            LocalDate.ofInstant(now, KST).atStartOfDay(KST).toInstant()

        /**
         * 캐시 키. 퀀트 시그널 조건은 사용자마다 결과가 다르므로(ADR-035 접근 규칙) 그때만 userId를 넣는다 —
         * 넣지 않으면 한 사용자의 구독 전략 신호로 거른 결과가 다른 사용자에게 캐시로 새어 나간다.
         */
        @JvmStatic
        fun cacheKey(tab: String, criteria: ScreenerCriteria, limit: Int, offset: Int, userId: Long?): String {
            // 호출부(컨트롤러)가 정규화한 조건을 넘긴다 — 여기서 검증 예외를 던지지 않는다
            val c = criteria
            val who = if (c.requiresQuantSignal) "u${userId ?: "anon"}" else "public"
            return "$tab:${c.cacheKey()}:$limit:$offset:$who"
        }
    }

    /**
     * 스크리너 결과를 5초간 캐싱한다(키는 [cacheKey]).
     * 조건이 잘못되면 IllegalArgumentException, 퀀트 시그널 조건을 비로그인으로 쓰면 ScreenerLoginRequiredException.
     */
    @Cacheable(
        cacheNames = [CacheConfig.SCREENER],
        key = "T(com.monticker.api.screener.application.ScreenerService).cacheKey(#tab, #criteria, #limit, #offset, #userId)",
    )
    fun getItems(
        tab: String = "realtime",
        criteria: ScreenerCriteria = ScreenerCriteria(),
        limit: Int = 20,
        offset: Int = 0,
        userId: Long? = null,
    ): ScreenerResult {
        val normalized = criteria.normalized()
        val effective = normalized.copy(sort = when (tab) {
            "movers" -> if (normalized.sort == "fall") "fall" else "rise"
            else     -> normalized.sort
        })
        val pageSize = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceIn(0, 1000)   // ScreenerController.MAX_OFFSET과 같은 상한(다른 호출자 방어)
        return Tracing.span("screener.getItems", mapOf(
            "screener.tab"           to tab,
            "screener.market"        to effective.market,
            "screener.sort"          to effective.sort,
            "screener.offset"        to safeOffset,
            "screener.marketCapTier" to effective.marketCapTier,
        )) { span ->
            val today = todayStart()
            val stockIdIn: Set<Long>? = if (effective.requiresQuantSignal) {
                val uid = userId ?: throw ScreenerLoginRequiredException("퀀트 시그널 조건은 로그인 후 쓸 수 있습니다")
                quantSignalFeedService.stockIdsWithSignalsSince(uid, today)
            } else null

            val items = repo.findItems(effective, pageSize, safeOffset, stockIdIn, today)
            val total = repo.count(effective, stockIdIn, today)
            span.setAttribute("screener.resultCount", items.size.toLong())
            ScreenerResult(enrich(items, today), total, safeOffset + items.size < total)
        }
    }

    /** 페이지에 오른 종목에 거래량 배수·오늘 이벤트를 붙인다(종목 수 ≤ 50). 실패해도 목록은 돌려준다. */
    private fun enrich(items: List<ScreenerItem>, today: Instant): List<ScreenerItem> {
        if (items.isEmpty()) return items
        val ctx = try {
            repo.findDayContext(items.map { it.stockId }, today)
        } catch (e: Exception) {
            log.warn("screener day context failed: {}", e.message)
            return items
        }
        return items.map { item ->
            val c = ctx[item.stockId] ?: return@map item
            item.copy(volumeMultiple = item.volumeMultiple ?: c.volumeMultiple, todayEvents = c.todayEvents)
        }
    }

    fun getSectors(market: String): List<SectorCount> {
        require(market in ScreenerCriteria.MARKETS) { "알 수 없는 market: $market" }
        return repo.findSectors(market)
    }

    /**
     * 섹터별 등락률(동일가중 평균)과 오늘(KST) 이벤트 수 — ADR-087. 30초 캐시(CacheConfig.SECTOR_PERFORMANCE).
     * 잘못된 market은 IllegalArgumentException(캐시에 넣기 전에 던지므로 키가 늘지 않는다).
     */
    @Cacheable(cacheNames = [CacheConfig.SECTOR_PERFORMANCE], key = "#market")
    fun getSectorPerformance(market: String): List<SectorPerformance> {
        require(market in ScreenerCriteria.MARKETS) { "알 수 없는 market: $market" }
        val from = todayStart()
        val to = LocalDate.ofInstant(from, KST).plusDays(1).atStartOfDay(KST).toInstant()
        return repo.findSectorPerformance(market, from, to)
    }

    /**
     * ES 종목 검색 결과로 스크리너 뷰를 구성한다.
     * ES → 종목 ID 목록 → DB 시세 조회 파이프라인.
     * ES 불가 시 DB LIKE 검색으로 폴백한다.
     *
     * market/marketCapTier — Quant Lab 유니버스 안에서만 검색하고 싶을 때 쓴다(예:
     * 룰셋의 universeJson이 "국내·대형주"면 검색도 그 범위로 좁힌다). ES는 이 두 기준으로
     * 필터링하지 못하므로 결과를 가져온 뒤 메모리에서 걸러낸다 — getItems()의 SQL
     * WHERE절과 동일한 기준(ScreenerRepository의 companion 함수)을 재사용해 두 경로의
     * 시가총액 구간 경계가 어긋나지 않게 한다.
     */
    fun search(
        query: String,
        sort: String = "amount",
        limit: Int   = 20,
        market: String = "all",
        marketCapTier: String = "all",
    ): ScreenerResult {
        return Tracing.span("screener.search", mapOf("screener.query" to query)) { span ->
            val stockIds = try {
                stockSearchService.search(query)
                    .take(limit)
                    .map { it.id }
            } catch (e: Exception) {
                searchMetrics.fallback("stocks")
                log.warn("ES stock search failed in screener, falling back to DB: {}", e.message)
                emptyList()
            }

            if (stockIds.isEmpty()) {
                span.setAttribute("screener.resultCount", 0L)
                return@span ScreenerResult(emptyList(), 0, false)
            }

            val items = repo.findItemsByStockIds(stockIds, sort)
                .filter { ScreenerRepository.matchesMarket(market, it.market) }
                .filter { ScreenerRepository.matchesMarketCapTier(marketCapTier, it.marketCap) }
            span.setAttribute("screener.resultCount", items.size.toLong())
            ScreenerResult(items, items.size, false)
        }
    }

    /**
     * 명시적 종목 ID 목록의 시세를 조회한다 (ES 검색 없이).
     * 관심종목 티커 스트립처럼 "이미 알고 있는 종목들"의 최신 시세가 필요할 때 사용.
     * 거래량 배수·오늘 이벤트도 붙인다(관심종목 화면의 거래량 배수 열).
     */
    fun getByStockIds(stockIds: List<Long>): ScreenerResult {
        if (stockIds.isEmpty()) return ScreenerResult(emptyList(), 0, false)
        val items = enrich(repo.findItemsByStockIds(stockIds), todayStart())
        return ScreenerResult(items, items.size, false)
    }
}

data class ScreenerResult(
    val items: List<ScreenerItem>,
    val total: Int,
    val hasMore: Boolean,
)
