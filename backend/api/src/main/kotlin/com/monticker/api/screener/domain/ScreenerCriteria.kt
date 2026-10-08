package com.monticker.api.screener.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 스크리너 "오늘 발생한 이벤트" 필터(ADR-072). 여러 개를 고르면 **모두** 만족하는 종목만 남는다(AND).
 * eventType이 null인 QUANT_SIGNAL은 stock_events가 아니라 내가 볼 수 있는 룰셋의 퀀트 신호다(ADR-035).
 */
enum class ScreenerEventFilter(val eventType: String?) {
    NEWS("NEWS_PUBLISHED"),
    DISCLOSURE("DISCLOSURE_PUBLISHED"),
    SENTIMENT("SENTIMENT_CHANGE"),
    QUANT_SIGNAL(null),
}

/**
 * 스크리너 조건 한 벌(ADR-072). 쿼리 파라미터와 저장 스크린(saved_screens.criteria)이 같은 타입·같은 검증을 쓴다 —
 * 저장 경로로 검증을 우회해 SQL에 이상한 값이 들어가는 일이 없게 한다.
 *
 * - minChange/maxChange: 전일 종가 대비 등락률(%) 범위
 * - minVolMult: 거래량 배수 하한 — 최신 일봉 거래량 ÷ 직전 20거래일 평균 거래량
 * - sectors: stocks.sector 정확히 일치(여러 개면 OR)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenerCriteria(
    val market: String = "all",
    val marketCapTier: String = "all",
    val sectors: List<String> = emptyList(),
    val minChange: Double? = null,
    val maxChange: Double? = null,
    val minVolMult: Double? = null,
    val events: List<ScreenerEventFilter> = emptyList(),
    val sort: String = "amount",
) {
    companion object {
        /** 시장 세그먼트. kospi/kosdaq은 국내(domestic)를 거래소별로 나눈 것 — SQL에는 리포지토리의 고정 조각으로만 들어간다 */
        val MARKETS = setOf("all", "domestic", "kospi", "kosdaq", "overseas")
        val MARKET_CAP_TIERS = setOf("all", "large", "mid", "small")
        val SORTS = setOf("amount", "volume", "rise", "fall", "volmult")
        const val MAX_SECTORS = 20
        const val MAX_SECTOR_LENGTH = 100
        const val CHANGE_FLOOR = -100.0
        const val CHANGE_CEIL = 1000.0
        const val VOL_MULT_CEIL = 1000.0

        /** 캐시 키 직렬화 — 속성 순서를 고정한다(필드 선언 순서가 바뀌어도 키가 흔들리지 않게). */
        private val CACHE_KEY_MAPPER = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)

        /** "a,b,c" → 목록. 빈 값은 버린다. */
        fun splitList(raw: String?): List<String> =
            raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        fun parseEvents(raw: String?): List<ScreenerEventFilter> = splitList(raw).map { token ->
            runCatching { ScreenerEventFilter.valueOf(token.uppercase()) }.getOrElse {
                throw IllegalArgumentException("알 수 없는 이벤트 필터: $token")
            }
        }
    }

    /** 검증하고 정규화한 사본(정렬·중복 제거 — 캐시 키가 순서에 흔들리지 않게). 잘못된 값이면 IllegalArgumentException. */
    fun normalized(): ScreenerCriteria {
        require(market in MARKETS) { "알 수 없는 market: $market" }
        require(marketCapTier in MARKET_CAP_TIERS) { "알 수 없는 marketCapTier: $marketCapTier" }
        require(sort in SORTS) { "알 수 없는 sort: $sort" }
        val cleanSectors = sectors.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        require(cleanSectors.size <= MAX_SECTORS) { "섹터는 최대 ${MAX_SECTORS}개까지 고를 수 있습니다" }
        require(cleanSectors.all { it.length <= MAX_SECTOR_LENGTH }) { "섹터 이름이 너무 깁니다" }
        listOfNotNull(minChange, maxChange).forEach {
            require(it.isFinite() && it in CHANGE_FLOOR..CHANGE_CEIL) { "등락률 범위는 $CHANGE_FLOOR ~ $CHANGE_CEIL% 입니다" }
        }
        if (minChange != null && maxChange != null) require(minChange <= maxChange) { "등락률 하한이 상한보다 큽니다" }
        minVolMult?.let { require(it.isFinite() && it in 0.0..VOL_MULT_CEIL) { "거래량 배수는 0 ~ $VOL_MULT_CEIL 입니다" } }
        return copy(sectors = cleanSectors, events = events.distinct().sortedBy { it.ordinal })
    }

    /** 오늘 이벤트 조건 중 stock_events로 거는 것 */
    @get:JsonIgnore
    val stockEventTypes: List<String> get() = events.mapNotNull { it.eventType }

    @get:JsonIgnore
    val requiresQuantSignal: Boolean get() = ScreenerEventFilter.QUANT_SIGNAL in events

    /** 일봉 기반 거래량 배수를 SQL에서 계산해야 하는가(필터·정렬에 쓰일 때만) */
    @get:JsonIgnore
    val needsVolumeMultipleInQuery: Boolean get() = minVolMult != null || sort == "volmult"

    /** 등락률·거래량 배수처럼 계산 컬럼에 거는 조건이 있는가 */
    @get:JsonIgnore
    val hasComputedFilter: Boolean get() = minChange != null || maxChange != null || minVolMult != null

    /**
     * 공유 캐시 키 — 정규화된 조건의 JSON을 SHA-256으로 줄인 64자 hex. 예전에는 섹터를 `|`, 필드를 `:`로 이어 붙여
     * 이스케이프가 없었다 — ["a|b"]와 ["a","b"]가 같은 키가 되어 서로 다른 조건이 같은 캐시 결과를 받았다(보안 리뷰 2026-10).
     * JSON은 문자열을 인용·이스케이프하므로 경계가 모호하지 않고, 해시는 길이를 고정한다. 호출자는 [normalized]한 사본으로 부른다.
     */
    fun cacheKey(): String {
        val json = CACHE_KEY_MAPPER.writeValueAsBytes(this)
        return java.security.MessageDigest.getInstance("SHA-256").digest(json).joinToString("") { "%02x".format(it) }
    }
}
