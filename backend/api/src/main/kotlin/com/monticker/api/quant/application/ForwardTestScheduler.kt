package com.monticker.api.quant.application

import com.monticker.api.common.calendar.TradingCalendar
import com.monticker.api.quant.domain.ForwardTestStatus
import com.monticker.api.quant.infrastructure.QuantForwardTestRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-024 — 장 마감(KST 15:30) 이후인 16:00에 실행 중인 포워드 테스트를 전부 순회 평가한다.
 * 이 시점 이후엔 더 이상 틱이 들어오지 않으므로 candles_1d의 "오늘" 로우가 사실상 확정값이 된다.
 */
@Component
class ForwardTestScheduler(
    private val forwardTestRepository: QuantForwardTestRepository,
    private val forwardTestService: ForwardTestService,
    private val calendar: TradingCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    fun evaluateAllRunning() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        // ADR-086 — 평일 휴장일에는 새 일봉이 없다. 평가하면 전 거래일 값을 "오늘" 결과로 한 번 더 남긴다.
        if (!calendar.isBusinessDay(today)) {
            log.info("포워드 테스트 일일 평가 건너뜀: {}은 휴장일({})", today, calendar.holidayName(today))
            return
        }
        val running = forwardTestRepository.findAllByStatus(ForwardTestStatus.RUNNING)
        log.info("포워드 테스트 일일 평가 시작: date={} count={}", today, running.size)
        for (ft in running) {
            try {
                forwardTestService.evaluateOne(ft, today)
            } catch (e: Exception) {
                log.error("포워드 테스트 평가 실패: forwardTestId={} error={}", ft.id, e.message, e)
            }
        }
    }
}
