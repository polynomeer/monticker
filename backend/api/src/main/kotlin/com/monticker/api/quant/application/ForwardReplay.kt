package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.QuantAuxData
import com.monticker.api.quant.domain.RuleDefinition
import com.monticker.api.quant.domain.SignalDirection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ReplaySignal(val date: LocalDate, val direction: SignalDirection)

/** 포워드 신호와 같은 기간 재실행(백테스트) 신호의 비교 결과. */
data class SignalMatch(val matched: Int, val compared: Int) {
    /** 양쪽 모두 신호가 없으면 비교할 게 없다 — 100%로 보이게 하지 않고 null. */
    val rate: Double? get() = if (compared == 0) null else matched.toDouble() / compared
}

/**
 * ADR-078 — 포워드 일치율. 포워드 테스트가 실제로 낸 신호를, 같은 기간·같은 룰·지금 저장된 데이터로
 * 포워드와 똑같은 절차(평가일마다 최근 [ForwardTestService.LOOKBACK_DAYS]일 일봉, [QuantDayStep])를
 * 다시 돌린 신호와 비교한다. 코드 경로가 같으므로 어긋남은 데이터 쪽 사건만 잡는다 — 평가 시점에
 * 캔들이 늦게 들어와 건너뛴 날, 나중에 보정된 시세, 스케줄러 누락 등.
 *
 * 일치율 = |포워드 ∩ 재실행| / |포워드 ∪ 재실행| — (평가일, 방향) 쌍의 자카드 지수.
 */
object ForwardReplay {

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** ForwardTestScheduler는 KST 16:00에 돈다 — 그 전에 시작했으면 시작일 당일부터 평가 대상이다. */
    private val EVALUATION_TIME: LocalTime = LocalTime.of(16, 0)

    fun firstEvaluationDate(startedAt: Instant): LocalDate {
        val local = startedAt.atZone(KST)
        return if (local.toLocalTime().isBefore(EVALUATION_TIME)) local.toLocalDate() else local.toLocalDate().plusDays(1)
    }

    internal fun replay(
        candles: List<DailyCandle>,
        ruleDef: RuleDefinition,
        initialCapital: Double,
        fromDate: LocalDate,
        toDate: LocalDate,
        lookbackDays: Long = ForwardTestService.LOOKBACK_DAYS,
        aux: QuantAuxData = QuantAuxData.EMPTY,
    ): List<ReplaySignal> {
        val sorted = candles.sortedBy { it.date }
        val signals = mutableListOf<ReplaySignal>()
        var cash = initialCapital
        var position: SimPosition? = null
        var lo = 0

        for (i in sorted.indices) {
            val day = sorted[i].date
            if (day < fromDate) continue
            if (day > toDate) break
            // ForwardTestService.evaluateOne과 같은 창: [day - lookbackDays, day]
            while (sorted[lo].date < day.minusDays(lookbackDays)) lo++
            val window = sorted.subList(lo, i + 1)

            when (val action = QuantDayStep.decide(ruleDef, window, window.lastIndex, cash, position, aux)) {
                is DayAction.Enter -> {
                    position = SimPosition(action.qty, action.fillPrice, day)
                    cash -= action.cost
                    signals += ReplaySignal(day, SignalDirection.BUY)
                }
                is DayAction.Exit -> {
                    cash += action.proceeds
                    position = null
                    signals += ReplaySignal(day, SignalDirection.SELL)
                }
                DayAction.Hold -> {}
            }
        }
        return signals
    }

    fun compare(forward: Collection<ReplaySignal>, replayed: Collection<ReplaySignal>): SignalMatch {
        val f = forward.toSet()
        val r = replayed.toSet()
        return SignalMatch(matched = f.intersect(r).size, compared = f.union(r).size)
    }
}
