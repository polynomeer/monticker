package com.monticker.worker.marketdata

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object MarketSchedule {

    private val KST = ZoneId.of("Asia/Seoul")
    private val ET  = ZoneId.of("America/New_York")

    // 한국 정규장: 09:00~15:30 KST (월~금)
    private val KR_OPEN  = LocalTime.of(9, 0)
    private val KR_CLOSE = LocalTime.of(15, 30)

    // 미국 정규장: 09:30~16:00 ET (월~금)
    private val US_OPEN  = LocalTime.of(9, 30)
    private val US_CLOSE = LocalTime.of(16, 0)

    enum class MarketStatus { PRE_MARKET, OPEN, POST_MARKET, CLOSED }

    data class TickConfig(
        val symbol: String,
        val market: String,
        val status: MarketStatus,
        val volatilityMultiplier: Double,
    )

    fun getTickConfig(symbol: String, market: String): TickConfig {
        return when (market) {
            "KOSPI", "KOSDAQ" -> krConfig(symbol, market)
            "NASDAQ", "NYSE"  -> usConfig(symbol, market)
            else              -> TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
        }
    }

    private fun krConfig(symbol: String, market: String): TickConfig {
        val now  = LocalDateTime.now(KST)
        val time = now.toLocalTime()
        val dow  = now.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
            return TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)

        return when {
            time.isBefore(LocalTime.of(7, 30))  -> TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
            time.isBefore(KR_OPEN)              -> TickConfig(symbol, market, MarketStatus.PRE_MARKET, 0.3)
            time.isBefore(KR_CLOSE)             -> TickConfig(symbol, market, MarketStatus.OPEN, 1.0)
            time.isBefore(LocalTime.of(18, 0))  -> TickConfig(symbol, market, MarketStatus.POST_MARKET, 0.2)
            else                                 -> TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
        }
    }

    private fun usConfig(symbol: String, market: String): TickConfig {
        val now  = LocalDateTime.now(ET)
        val time = now.toLocalTime()
        val dow  = now.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
            return TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)

        return when {
            time.isBefore(LocalTime.of(4, 0))   -> TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
            time.isBefore(US_OPEN)              -> TickConfig(symbol, market, MarketStatus.PRE_MARKET, 0.2)
            time.isBefore(US_CLOSE)             -> TickConfig(symbol, market, MarketStatus.OPEN, 1.0)
            time.isBefore(LocalTime.of(20, 0))  -> TickConfig(symbol, market, MarketStatus.POST_MARKET, 0.15)
            else                                 -> TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
        }
    }

    fun isAnyMarketOpen(): Boolean {
        val krOpen = getTickConfig("TEST", "KOSPI").status != MarketStatus.CLOSED
        val usOpen = getTickConfig("TEST", "NASDAQ").status != MarketStatus.CLOSED
        return krOpen || usOpen
    }
}
