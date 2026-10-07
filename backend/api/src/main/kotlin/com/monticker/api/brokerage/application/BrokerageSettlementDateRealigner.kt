package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.common.calendar.KrxCalendar
import com.monticker.api.common.calendar.MarketCalendarChangedEvent
import com.monticker.api.common.calendar.SettlementDateRealignment
import com.monticker.api.common.calendar.TradingCalendar
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * ADR-086 — 실거래 PENDING 정산의 정산일을 KRX 캘린더 기준으로 다시 맞춘다(paper와 같은 규칙, [SettlementDateRealignment]).
 *
 * brokerage_settlements.settle_date는 증권사가 준 값이 아니라 우리가 체결 확인 시점에 "주말만 건너뛴 T+2"로 계산한 값이었다.
 * 공휴일에 떨어진 정산일은 원장에 휴장일 정산으로 기록된다 — 증권사의 실제 결제일과 어긋난다.
 * 기준 시각은 정산 행을 만든 시각(created_at, 예전 계산의 기준과 같다). 정산일만 바꾸고 원장은 건드리지 않는다.
 */
@Component
class BrokerageSettlementDateRealigner(
    private val repository: BrokerageSettlementRepository,
    private val calendar: TradingCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal var clock: Clock = Clock.systemUTC()

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() = runSafely("startup")

    @EventListener
    fun onCalendarChanged(event: MarketCalendarChangedEvent) = runSafely("calendar-changed")

    private fun runSafely(trigger: String) {
        try {
            val moved = realign()
            if (moved > 0) log.warn("brokerage settlement dates realigned to KRX calendar: moved={} trigger={}", moved, trigger)
        } catch (e: Exception) {
            log.error("brokerage settlement date realign failed (trigger={}): {}", trigger, e.message, e)
        }
    }

    fun realign(): Int {
        val today = LocalDate.now(clock.withZone(KrxCalendar.ZONE))
        val rows = repository.findUpcomingPendingDates(today).map { SettlementDateRealignment.Row(it.id, it.settleDate, it.createdAt) }
        return SettlementDateRealignment.realign(today, rows, calendar, repository::moveSettleDate) { row, newDate ->
            log.info("brokerage settlement {} settle_date {} -> {}", row.id, row.settleDate, newDate)
        }
    }
}
