package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.StockPriceProvider
import com.monticker.api.marketdata.infrastructure.CandleRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** 최신가와 그 가격의 전 거래일 대비 등락. [prevClose]를 모르면 둘 다 null(0%로 위장하지 않는다). */
data class PriceView(val tick: PriceTick, val prevClose: BigDecimal?, val changeRate: Double?)

@Service
class MarketDataService(
    private val priceProvider: StockPriceProvider,
    private val candleRepository: CandleRepository,
) {
    fun getLatestPrice(stockId: Long, symbol: String): PriceTick? =
        priceProvider.getLatestPrice(stockId, symbol)

    /**
     * 최신가 + 전일 종가·등락률(%). 전일은 **그 가격이 난 거래일**의 직전 거래일이다 — 주말·장 마감 뒤에는 마지막 거래일의
     * 등락을 보여 준다(관행). KRX(KOSPI·KOSDAQ)만 계산한다: 일봉 버킷이 KST 자정이라 KST 자정을 걸치는 미국장 세션은 일봉이
     * 둘로 쪼개져 전일 종가가 틀린다(design-review T5).
     */
    fun getPriceView(stockId: Long, symbol: String, market: String): PriceView? {
        val tick = priceProvider.getLatestPrice(stockId, symbol) ?: return null
        if (market != "KOSPI" && market != "KOSDAQ") return PriceView(tick, null, null)
        val sessionStart = LocalDate.ofInstant(tick.tradeTime, KST).atStartOfDay(KST).toInstant()
        val prevClose = runCatching { candleRepository.findPreviousClose(stockId, sessionStart) }.getOrNull()
            ?: return PriceView(tick, null, null)
        val rate = tick.price.subtract(prevClose).divide(prevClose, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).toDouble()
        return PriceView(tick, prevClose, rate)
    }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
