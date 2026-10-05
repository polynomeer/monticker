package com.monticker.api.event.application

import com.monticker.api.event.domain.StockEvent
import com.monticker.api.event.infrastructure.EventCandleWindow
import com.monticker.api.event.infrastructure.EventMarketContextRepository
import com.monticker.api.event.infrastructure.EventMarketContextRepository.EventRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 이벤트 피드의 "이벤트 구간 변동"·"거래량 배수".
 *
 * - 구간 변동률(%) = 이벤트 직전 1분봉 종가 → 이벤트 후 30분(아직 안 지났으면 지금)까지 마지막 1분봉 종가
 * - 거래량 배수 = 이벤트 분부터 5분 평균 1분 거래량 ÷ 직전 60분 평균 1분 거래량
 *
 * 탐지기 metadata(ratio 등)는 이벤트 유형마다 의미가 달라(틱 간 변동 대비 EMA 등) 쓰지 않는다 —
 * 모든 유형에 같은 정의를 적용해야 피드에서 열끼리 비교할 수 있다. 데이터가 모자라면 null(화면은 "—").
 */
@Service
class EventMarketContextService(private val repo: EventMarketContextRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun contextFor(events: List<StockEvent>): Map<Long, EventMarketContext> {
        if (events.isEmpty()) return emptyMap()
        return try {
            repo.findWindows(events.map { EventRef(it.id, it.stockId, it.eventTime) })
                .associate { it.eventId to compute(it) }
        } catch (e: Exception) {
            // 보조 지표다 — 계산 실패가 이벤트 피드 자체를 깨면 안 된다.
            log.warn("event market context failed: {}", e.message)
            emptyMap()
        }
    }

    companion object {
        /** 기준 거래량이 이보다 작으면 배수를 내지 않는다(0에 가까운 분모로 수천 배가 찍히는 것 방지). */
        const val MIN_BASELINE_VOLUME = 1.0

        fun compute(w: EventCandleWindow): EventMarketContext {
            val change = if (w.baseClose != null && w.endClose != null && w.baseClose > BigDecimal.ZERO) {
                w.endClose.subtract(w.baseClose).divide(w.baseClose, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).toDouble()
            } else null
            val volMult = if (w.eventAvgVolume != null && w.baselineAvgVolume != null && w.baselineAvgVolume >= MIN_BASELINE_VOLUME) {
                w.eventAvgVolume / w.baselineAvgVolume
            } else null
            return EventMarketContext(change, volMult)
        }
    }
}

data class EventMarketContext(
    val windowChangePct: Double?,
    val volumeMultiple: Double?,
)
