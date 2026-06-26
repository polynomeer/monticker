package com.monticker.worker.marketdata

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 한국/미국 증시 장 시간 관리.
 *
 * 장 상태에 따라 틱 생성 여부와 변동성 배율(volatilityMultiplier)을 결정한다.
 *
 *   CLOSED       : 틱 생성 안 함 (주말, 장 마감 이후)
 *   PRE_MARKET   : 변동성 0.3× (시간외 단일가 시뮬레이션)
 *   OPEN         : 변동성 1.0× (정규장)
 *   POST_MARKET  : 변동성 0.2× (시간외)
 *
 * 주의: 한국 공휴일은 현재 미반영. 실 서비스에서는 KRX 휴장일 API 연동 필요.
 */
object MarketSchedule {

    private val KST = ZoneId.of("Asia/Seoul")
    private val ET  = ZoneId.of("America/New_York")

    enum class MarketStatus { PRE_MARKET, OPEN, POST_MARKET, CLOSED }

    data class TickConfig(
        val symbol: String,
        val market: String,
        val status: MarketStatus,
        val volatilityMultiplier: Double,
    )

    fun getTickConfig(symbol: String, market: String): TickConfig = when (market) {
        "KOSPI", "KOSDAQ" -> krConfig(symbol, market)
        "NASDAQ", "NYSE"  -> usConfig(symbol, market)
        else              -> TickConfig(symbol, market, MarketStatus.OPEN, 1.0)  // 기본값: 항상 열림
    }

    private fun krConfig(symbol: String, market: String): TickConfig {
        val now = LocalDateTime.now(KST)
        val t   = now.toLocalTime()
        if (now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            return TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
        return when {
            t.isBefore(LocalTime.of(7, 30))  -> TickConfig(symbol, market, MarketStatus.CLOSED,      0.0)
            t.isBefore(LocalTime.of(9,  0))  -> TickConfig(symbol, market, MarketStatus.PRE_MARKET,  0.3)
            t.isBefore(LocalTime.of(15, 30)) -> TickConfig(symbol, market, MarketStatus.OPEN,        1.0)
            t.isBefore(LocalTime.of(18,  0)) -> TickConfig(symbol, market, MarketStatus.POST_MARKET, 0.2)
            else                              -> TickConfig(symbol, market, MarketStatus.CLOSED,      0.0)
        }
    }

    private fun usConfig(symbol: String, market: String): TickConfig {
        val now = LocalDateTime.now(ET)
        val t   = now.toLocalTime()
        if (now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            return TickConfig(symbol, market, MarketStatus.CLOSED, 0.0)
        return when {
            t.isBefore(LocalTime.of(4,  0))  -> TickConfig(symbol, market, MarketStatus.CLOSED,      0.0)
            t.isBefore(LocalTime.of(9, 30))  -> TickConfig(symbol, market, MarketStatus.PRE_MARKET,  0.2)
            t.isBefore(LocalTime.of(16,  0)) -> TickConfig(symbol, market, MarketStatus.OPEN,        1.0)
            t.isBefore(LocalTime.of(20,  0)) -> TickConfig(symbol, market, MarketStatus.POST_MARKET, 0.15)
            else                              -> TickConfig(symbol, market, MarketStatus.CLOSED,      0.0)
        }
    }
}
