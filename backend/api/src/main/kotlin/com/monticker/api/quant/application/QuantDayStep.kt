package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.QuantAuxData
import com.monticker.api.quant.domain.RuleDefinition
import java.time.LocalDate

/** 시뮬레이션 중 보유 포지션 — 백테스트·포워드 테스트·재실행(replay)이 같은 모양을 쓴다. */
internal data class SimPosition(
    val qty: Int,
    /** 슬리피지가 반영된 체결가 */
    val entryPrice: Double,
    val entryDate: LocalDate,
)

internal sealed interface DayAction {
    data object Hold : DayAction

    data class Enter(val qty: Int, val fillPrice: Double, val commission: Double) : DayAction {
        val cost: Double get() = qty * fillPrice + commission
    }

    data class Exit(val qty: Int, val fillPrice: Double, val commission: Double, val reason: String) : DayAction {
        val proceeds: Double get() = qty * fillPrice - commission
    }
}

/**
 * ADR-078 — 하루치 매매 판단의 단일 구현. 예전에는 QuantBacktestEngine과 ForwardTestService가
 * 같은 판단을 각자 복붙해 들고 있었고, 그 사이에 차이가 생겨 있었다(백테스트는 같은 날 청산 후
 * 재진입 가능, 포워드는 불가 / 포워드는 수수료를 현금에서 빼지 않음). 같은 룰이 백테스트와 포워드에서
 * 다르게 움직이면 "포워드 일치율"은 데이터가 아니라 코드 차이를 재게 된다 — 판단은 여기 한 곳에만 둔다.
 *
 * 규칙: 하루에 한 가지 행동만 한다(보유 중이면 청산 판단만, 미보유면 진입 판단만). 체결가는 종가에
 * 슬리피지를 반영하고 수수료는 현금에서 뺀다.
 */
internal object QuantDayStep {

    fun decide(
        ruleDef: RuleDefinition,
        candles: List<DailyCandle>,
        idx: Int,
        cash: Double,
        position: SimPosition?,
        aux: QuantAuxData = QuantAuxData.EMPTY,
    ): DayAction {
        val price = candles[idx].close.toDouble()

        if (position != null) {
            if (!RuleEvaluator.evaluateExit(ruleDef.exitRules, candles, idx, position.entryPrice, price, aux)) {
                return DayAction.Hold
            }
            val fill = price * (1 - QuantBacktestEngine.SLIPPAGE_RATE)
            return DayAction.Exit(
                qty        = position.qty,
                fillPrice  = fill,
                commission = position.qty * fill * QuantBacktestEngine.COMMISSION_RATE,
                reason     = "SIGNAL",
            )
        }

        if (cash <= price) return DayAction.Hold
        if (!RuleEvaluator.evaluateEntry(ruleDef.entryRules, candles, idx, aux)) return DayAction.Hold

        val ratio      = ruleDef.positionSizing.value / 100.0
        val fill       = price * (1 + QuantBacktestEngine.SLIPPAGE_RATE)
        val qty        = (cash * ratio / fill).toInt().coerceAtLeast(1)
        val enter      = DayAction.Enter(qty, fill, qty * fill * QuantBacktestEngine.COMMISSION_RATE)
        return if (enter.cost <= cash) enter else DayAction.Hold
    }
}
