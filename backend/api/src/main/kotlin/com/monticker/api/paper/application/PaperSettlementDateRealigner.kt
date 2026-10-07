package com.monticker.api.paper.application

import com.monticker.api.common.calendar.KrxCalendar
import com.monticker.api.common.calendar.MarketCalendarChangedEvent
import com.monticker.api.common.calendar.SettlementDateRealignment
import com.monticker.api.common.calendar.TradingCalendar
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * ADR-086 — 이미 만들어진 PENDING 모의 정산의 정산일을 캘린더 기준으로 다시 맞춘다.
 *
 * 예전 계산(주말만 건너뜀, 서버 시간대의 "오늘")으로 잡힌 정산일은 공휴일에 떨어질 수 있고, 그러면 휴장일에 정산된다.
 * 기동 직후 한 번, 그리고 캘린더 데이터가 바뀔 때마다(임시공휴일 추가 등) 돈다. 기준 시각은 체결 시각(paper_trades.traded_at).
 * 규칙은 [SettlementDateRealignment]. 정산일만 바뀐다 — 현금·원장은 정산 시점에만 움직이므로 이 보정은 돈을 옮기지 않는다.
 */
@Component
class PaperSettlementDateRealigner(
    private val repository: PaperSettlementRepository,
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
            if (moved > 0) log.warn("paper settlement dates realigned to KRX calendar: moved={} trigger={}", moved, trigger)
        } catch (e: Exception) {
            // 보정 실패가 기동을 막으면 안 된다. 정산 배치는 휴장일에 돌지 않으므로(BatchJobScheduler) 다음 기회에 다시 맞춘다.
            log.error("paper settlement date realign failed (trigger={}): {}", trigger, e.message, e)
        }
    }

    /** 옮긴 건수. 행마다 따로 커밋한다(moveSettleDate가 자체 트랜잭션). */
    fun realign(): Int {
        val today = LocalDate.now(clock.withZone(KrxCalendar.ZONE))
        val rows = repository.findUpcomingPendingDates(today).map { SettlementDateRealignment.Row(it.id, it.settleDate, it.tradedAt) }
        return SettlementDateRealignment.realign(today, rows, calendar, repository::moveSettleDate) { row, newDate ->
            log.info("paper settlement {} settle_date {} -> {}", row.id, row.settleDate, newDate)
        }
    }
}
