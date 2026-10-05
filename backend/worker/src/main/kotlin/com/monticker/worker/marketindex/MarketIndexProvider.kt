package com.monticker.worker.marketindex

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 수집 대상 지수·환율. 코드는 api(GET /api/market/indices)와 문자열로 맞춘다. */
enum class MarketIndexCode(val displayName: String) {
    KOSPI("코스피"),
    KOSDAQ("코스닥"),
    USDKRW("원/달러"),
}

/** 공급자가 돌려주는 시세 한 건. */
data class MarketIndexTick(
    val code: MarketIndexCode,
    val value: BigDecimal,
    val asOf: Instant,
)

/**
 * ADR-071 — 지수·환율 시세 공급자. 수집기(MarketIndexCollector)는 이 인터페이스만 안다.
 *
 * 실시세 공급자(KIS 업종지수 등)는 요청·응답 규격을 실제 키로 확인한 뒤에 이 인터페이스 뒤에 붙인다.
 * 지금 저장소에는 그 규격이 없어 Mock만 있다 — Mock 값은 `isMock=true`로 저장돼 화면이 "모의"로 표시한다.
 */
interface MarketIndexProvider {
    /** market_index_quotes.source 값 */
    val source: String
    val isMock: Boolean

    /**
     * 최신 시세. 새 값이 없으면(장 마감 등) 빈 목록을 돌려준다.
     * @param previous 지수별 마지막 저장 값 — Mock은 여기서 이어서 움직인다(재시작해도 값이 튀지 않게). 실시세 공급자는 무시한다.
     */
    fun fetch(previous: Map<MarketIndexCode, BigDecimal>, now: Instant = Instant.now()): List<MarketIndexTick>

    /**
     * 과거 일별 종가(오래된 날짜 → 최근 날짜). 테이블이 비어 있을 때 한 번 채우는 데 쓴다.
     * 실시세 공급자가 이력 API를 제공하지 않으면 빈 목록.
     */
    fun history(code: MarketIndexCode, endExclusive: LocalDate, tradingDays: Int): List<Pair<LocalDate, BigDecimal>>
}
