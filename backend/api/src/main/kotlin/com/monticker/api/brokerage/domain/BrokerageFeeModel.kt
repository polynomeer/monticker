package com.monticker.api.brokerage.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 실거래 체결의 수수료·세금 모델 — 정산 행(BrokerageSettlement)을 만들 때 쓰는 바로 그 식이다. 리밸런싱 미리보기의 예상 거래비용도
 * 같은 식을 쓴다(체결 후 장부에 남을 값과 미리보기가 어긋나지 않게).
 *
 * 증권사·계좌별 실제 수수료율이 아니다(증권사별 수수료율은 외부 데이터가 필요해 보류 — design-rollout-plan §5).
 * 수수료 0.015%, 매도 거래세 0.18%, 둘 다 원 단위 올림.
 */
object BrokerageFeeModel {
    val FEE_RATE = BigDecimal("0.00015")
    val SELL_TAX_RATE = BigDecimal("0.0018")

    fun fee(gross: BigDecimal): BigDecimal = gross.multiply(FEE_RATE).setScale(0, RoundingMode.UP)

    fun tax(side: OrderSide, gross: BigDecimal): BigDecimal =
        if (side == OrderSide.SELL) gross.multiply(SELL_TAX_RATE).setScale(0, RoundingMode.UP) else BigDecimal.ZERO
}
