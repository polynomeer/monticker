package com.monticker.api.screener.api

import com.monticker.api.screener.application.ScreenerResult
import com.monticker.api.screener.application.ScreenerService
import com.monticker.api.common.aop.RateLimited
import com.monticker.api.screener.application.SavedScreen
import com.monticker.api.screener.application.SavedScreenService
import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.SectorCount
import com.monticker.api.screener.infrastructure.SectorPerformance
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@Validated
@RestController
@RequestMapping("/api/screener")
class ScreenerController(
    private val screenerService: ScreenerService,
    private val savedScreenService: SavedScreenService,
) {

    /** 로그인했으면 사용자 id(이 경로는 비로그인 공개라 없을 수 있다) */
    private fun optionalUserId(): Long? = SecurityContextHolder.getContext().authentication?.principal as? Long

    private fun requireUserId(): Long = optionalUserId()
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다")

    /** 공개 경로라 깊은 OFFSET 스캔을 비로그인으로 반복시킬 수 있다 — 상한을 둔다(보안 리뷰 2026-10). 음수는 0으로 본다. */
    private fun requireOffset(offset: Int) {
        require(offset <= MAX_OFFSET) { "offset은 $MAX_OFFSET 이하여야 합니다" }
    }

    companion object {
        const val MAX_OFFSET = 1000
    }

    /**
     * 시세 기반 스크리너. 조건은 ADR-072.
     *
     * GET /api/screener?tab=realtime&market=domestic&sort=amount
     * GET /api/screener?sectors=반도체,2차전지&minChange=1&maxChange=30&minVolMult=2&events=NEWS,QUANT_SIGNAL
     *
     * events: NEWS | DISCLOSURE | SENTIMENT | QUANT_SIGNAL(로그인 필요, 내 룰셋·구독 전략 신호만) — 모두 만족(AND)
     */
    @GetMapping
    fun getScreener(
        @RequestParam(defaultValue = "realtime") tab: String,
        @RequestParam(defaultValue = "all")      market: String,
        @RequestParam(defaultValue = "amount")   sort: String,
        @RequestParam(defaultValue = "20")       limit: Int,
        @RequestParam(defaultValue = "0")        offset: Int,
        @RequestParam(defaultValue = "all")      marketCapTier: String,
        @RequestParam(required = false)          sectors: String?,
        @RequestParam(required = false)          minChange: Double?,
        @RequestParam(required = false)          maxChange: Double?,
        @RequestParam(required = false)          minVolMult: Double?,
        @RequestParam(required = false)          events: String?,
    ): ResponseEntity<ScreenerResponse> {
        requireOffset(offset)
        val criteria = ScreenerCriteria(
            market        = market,
            marketCapTier = marketCapTier,
            sectors       = ScreenerCriteria.splitList(sectors),
            minChange     = minChange,
            maxChange     = maxChange,
            minVolMult    = minVolMult,
            events        = ScreenerCriteria.parseEvents(events),
            sort          = sort,
        ).normalized()   // 잘못된 값은 IllegalArgumentException → 400
        return run(tab, criteria, limit, offset)
    }

    private fun run(tab: String, criteria: ScreenerCriteria, limit: Int, offset: Int): ResponseEntity<ScreenerResponse> {
        val userId = optionalUserId()
        if (criteria.requiresQuantSignal && userId == null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "퀀트 시그널 조건은 로그인 후 쓸 수 있습니다")
        }
        val result = screenerService.getItems(tab, criteria, limit.coerceIn(1, 50), offset.coerceAtLeast(0), userId)
        return ResponseEntity.ok(result.toResponse())
    }

    /** 섹터 칩 목록 — 활성 종목의 섹터와 종목 수(많은 순, 최대 100개). GET /api/screener/sectors?market=domestic */
    @GetMapping("/sectors")
    fun sectors(@RequestParam(defaultValue = "all") market: String): ResponseEntity<List<SectorCount>> =
        ResponseEntity.ok(screenerService.getSectors(market))

    /**
     * 섹터별 등락률 — 홈 섹터 히트맵(ADR-087). 종목 등락률은 이 스크리너 목록과 같은 식(최신 1분봉 종가 vs 직전 일봉 종가),
     * 섹터 값은 등락률이 있는 종목의 단순 평균(동일가중). 종목 수 많은 순 최대 100개.
     *
     * GET /api/screener/sectors/performance?market=domestic
     */
    @GetMapping("/sectors/performance")
    fun sectorPerformance(@RequestParam(defaultValue = "all") market: String): ResponseEntity<SectorPerformanceResponse> =
        ResponseEntity.ok(
            SectorPerformanceResponse(
                market      = market,
                weighting   = "EQUAL",
                eventsSince = ScreenerService.todayStart(),
                sectors     = screenerService.getSectorPerformance(market),
                updatedAt   = Instant.now(),
            ),
        )

    // ── 저장한 스크린(ADR-072) — 로그인 필요(SecurityConfig) ───────────────────────

    @GetMapping("/saved")
    fun listSaved(): ResponseEntity<List<SavedScreenResponse>> =
        ResponseEntity.ok(savedScreenService.list(requireUserId()).map { SavedScreenResponse.from(it) })

    @PostMapping("/saved")
    @RateLimited(limit = 30, windowSec = 3600, keyPrefix = "screener.saved")
    fun createSaved(@RequestBody req: SavedScreenRequest): ResponseEntity<SavedScreenResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(SavedScreenResponse.from(savedScreenService.create(requireUserId(), req.name, req.criteria)))

    @PutMapping("/saved/{id}")
    fun updateSaved(@PathVariable id: Long, @RequestBody req: SavedScreenRequest): ResponseEntity<SavedScreenResponse> =
        ResponseEntity.ok(SavedScreenResponse.from(savedScreenService.update(requireUserId(), id, req.name, req.criteria)))

    @DeleteMapping("/saved/{id}")
    fun deleteSaved(@PathVariable id: Long): ResponseEntity<Void> {
        savedScreenService.delete(requireUserId(), id)
        return ResponseEntity.noContent().build()
    }

    /** 저장한 조건으로 실행 — GET /api/screener/saved/{id}/run?tab=realtime&limit=20&offset=0 */
    @GetMapping("/saved/{id}/run")
    fun runSaved(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "realtime") tab: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<ScreenerResponse> {
        requireOffset(offset)
        val saved = savedScreenService.get(requireUserId(), id)
        return run(tab, saved.criteria, limit, offset)
    }

    /**
     * ES 키워드 검색 스크리너.
     * 종목 이름·심볼·섹터 키워드로 ES에서 종목을 검색한 뒤 시세 데이터를 붙여 반환한다.
     *
     * GET /api/screener/search?query=반도체
     * GET /api/screener/search?query=삼성&sort=volume
     */
    @GetMapping("/search")
    fun searchScreener(
        @RequestParam query: String,
        @RequestParam(defaultValue = "amount") sort: String,
        @RequestParam(defaultValue = "20")     limit: Int,
        @RequestParam(defaultValue = "all")    market: String,
        @RequestParam(defaultValue = "all")    marketCapTier: String,
    ): ResponseEntity<ScreenerResponse> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        val result = screenerService.search(query, sort, limit.coerceIn(1, 50), market, marketCapTier)
        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * 명시적 종목 ID 목록의 시세를 조회한다 (관심종목 티커 스트립 등).
     *
     * GET /api/screener/quotes?ids=1,2,3
     */
    @GetMapping("/quotes")
    fun getQuotes(@RequestParam ids: String): ResponseEntity<ScreenerResponse> {
        val stockIds = ids.split(",").mapNotNull { it.trim().toLongOrNull() }.take(50)
        val result = screenerService.getByStockIds(stockIds)
        return ResponseEntity.ok(result.toResponse())
    }

    private fun ScreenerResult.toResponse() = ScreenerResponse(
        items     = items.map { ScreenerItemResponse.from(it) },
        total     = total,
        hasMore   = hasMore,
        updatedAt = Instant.now(),
    )
}

data class SectorPerformanceResponse(
    val market: String,
    /** 섹터 값의 가중 방식 — EQUAL(종목 단순 평균) */
    val weighting: String,
    /** sectors[].eventCount를 세기 시작한 시각(오늘 KST 자정) */
    val eventsSince: Instant,
    val sectors: List<SectorPerformance>,
    val updatedAt: Instant,
)

data class ScreenerResponse(
    val items: List<ScreenerItemResponse>,
    val total: Int,
    val hasMore: Boolean,
    val updatedAt: Instant,
)

data class ScreenerItemResponse(
    val rank: Int,
    val stockId: Long,
    val symbol: String,
    val name: String,
    val market: String,
    val sector: String?,
    val price: BigDecimal,
    val changeRate: Double,
    val changeAmount: BigDecimal,
    val volume: Long,
    val amount: BigDecimal,
    val buyRatio: Int,
    val sellRatio: Int,
    val marketCap: Long?,
    val per: BigDecimal?,
    val pbr: BigDecimal?,
    val isFundamentalsMocked: Boolean,
    /** 최신 일봉 거래량 ÷ 직전 20거래일 평균(ADR-072). 없으면 null */
    val volumeMultiple: Double?,
    /** 오늘(KST) 생긴 이벤트 유형(중요도 높은 순) */
    val todayEvents: List<String>,
) {
    companion object {
        fun from(i: ScreenerItem) = ScreenerItemResponse(
            rank         = i.rank,
            stockId      = i.stockId,
            symbol       = i.symbol,
            name         = i.name,
            market       = i.market,
            sector       = i.sector,
            price        = i.price,
            changeRate   = i.changeRate,
            changeAmount = i.changeAmount,
            volume       = i.volume,
            amount       = i.amount,
            buyRatio     = i.buyRatio,
            sellRatio    = i.sellRatio,
            marketCap    = i.marketCap,
            per          = i.per,
            pbr          = i.pbr,
            isFundamentalsMocked = i.isFundamentalsMocked,
            volumeMultiple = i.volumeMultiple,
            todayEvents    = i.todayEvents,
        )
    }
}

data class SavedScreenRequest(
    val name: String,
    val criteria: ScreenerCriteria,
)

data class SavedScreenResponse(
    val id: Long,
    val name: String,
    val criteria: ScreenerCriteria,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(s: SavedScreen) = SavedScreenResponse(s.id, s.name, s.criteria, s.createdAt, s.updatedAt)
    }
}
