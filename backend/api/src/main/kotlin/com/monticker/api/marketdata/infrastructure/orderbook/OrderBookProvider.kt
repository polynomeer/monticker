package com.monticker.api.marketdata.infrastructure.orderbook

import java.math.BigDecimal
import java.time.Instant

interface OrderBookProvider {
    /**
     * 호가 데이터를 반환한다. 데이터를 제공할 수 없으면 null.
     * @param symbol  종목코드 (예: "005930")
     * @param market  시장 ("KOSPI" | "KOSDAQ" | "NASDAQ" 등)
     * @param refPrice DB에서 조회한 현재가 (provider가 가격을 모를 때 기준값)
     */
    fun getOrderBook(symbol: String, market: String, refPrice: BigDecimal): OrderBookSnapshot?
}

data class OrderBookSnapshot(
    val asks: List<OrderLevel>,   // 매도호가: 낮은 가격부터
    val bids: List<OrderLevel>,   // 매수호가: 높은 가격부터
    val updatedAt: Instant,
    val source: DataSource,
)

data class OrderLevel(val price: BigDecimal, val quantity: Long)

enum class DataSource {
    /** KIS WebSocket 실시간 호가 */
    KIS_REALTIME,
    /** Yahoo Finance 15분 지연 + 시뮬레이션 호가 깊이 */
    YAHOO_FINANCE,
    /** 완전 랜덤 Mock */
    MOCK,
}
