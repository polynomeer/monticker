package com.monticker.api.brokerage.domain

import java.math.BigDecimal

/**
 * ADR-081 — KRX(유가증권·코스닥) 주식 지정가의 거래소 규칙. 순수 계산이라 데이터 출처와 무관하게 언제나 맞다.
 *
 * - 호가 단위: 2023-01-25 개편 이후 유가·코스닥 공통 7단계. 가격이 속한 구간의 단위의 배수여야 한다.
 * - 가격제한폭: 기준가 ±30%. 실제 상·하한가는 호가 단위로 절사돼 [기준가×0.7, 기준가×1.3] **안쪽**에 있다 — 그래서 이 구간 밖은
 *   기준가가 맞는 한 반드시 거래소 거부다. 구간 안이지만 실제 한도 밖인 가격(절사 차이)은 증권사가 거부한다(fail-open 쪽).
 *
 * ETF·ETN은 단위가 다르다(2,000원 이상 5원). 종목 마스터(`StockMasterCollector`, KRX MDCSTAT01901)는 주식 시장 상장 종목만
 * 적재해 ETF가 없다 — ETF를 적재하게 되면 상품 구분 컬럼과 함께 이 규칙을 나눠야 한다(ADR-081 Revisit When).
 */
object KrxPriceRules {
    private val LIMIT_RATE = BigDecimal("0.30")

    fun isKrxMarket(market: String?): Boolean = market == "KOSPI" || market == "KOSDAQ"

    fun tickSize(price: BigDecimal): BigDecimal = BigDecimal(
        when {
            price < BigDecimal(2_000) -> 1
            price < BigDecimal(5_000) -> 5
            price < BigDecimal(20_000) -> 10
            price < BigDecimal(50_000) -> 50
            price < BigDecimal(200_000) -> 100
            price < BigDecimal(500_000) -> 500
            else -> 1_000
        },
    )

    /** 원 단위 정수이고 그 가격대의 호가 단위 배수인가. 0 이하는 false. */
    fun isOnTick(price: BigDecimal): Boolean {
        if (price.signum() <= 0) return false
        if (price.stripTrailingZeros().scale() > 0) return false
        return price.remainder(tickSize(price)).signum() == 0
    }

    /** 기준가 [base]로 계산한 가격제한폭의 바깥 경계 — 이 구간 밖은 거래소가 받지 않는다. */
    fun band(base: BigDecimal): ClosedRange<BigDecimal> {
        require(base.signum() > 0) { "기준가는 0보다 커야 합니다." }
        val width = base.multiply(LIMIT_RATE)
        return base.subtract(width)..base.add(width)
    }
}
