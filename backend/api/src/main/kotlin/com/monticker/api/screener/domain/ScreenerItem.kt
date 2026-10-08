package com.monticker.api.screener.domain

data class ScreenerItem(
    val rank: Int,
    val stockId: Long,
    val symbol: String,
    val name: String,
    val market: String,
    val sector: String?,
    val price: java.math.BigDecimal,
    val prevClose: java.math.BigDecimal?,
    val changeRate: Double,           // %
    val changeAmount: java.math.BigDecimal,
    val volume: Long,
    val amount: java.math.BigDecimal, // 거래대금
    /** 실제 매수/매도(외국인·기관) 수급 데이터 소스가 없다 — 지어낸 값을 보내지 않고 null. 화면은 "—"·준비 중 */
    val buyRatio: Int? = null,
    val sellRatio: Int? = null,
    val marketCap: Long?,             // 시가총액 (원 단위) — KOSPI/KOSDAQ만 존재
    val per: java.math.BigDecimal?,
    val pbr: java.math.BigDecimal?,
    val isFundamentalsMocked: Boolean,
    /** 최신 일봉 거래량 ÷ 직전 20거래일 평균(ADR-072). 일봉이 모자라면 null */
    val volumeMultiple: Double? = null,
    /** 오늘(KST) 이 종목에 생긴 stock_events 유형 */
    val todayEvents: List<String> = emptyList(),
)
