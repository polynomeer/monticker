package com.monticker.api.paper.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class SettlementCalculation(
    val grossAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val netAmount: BigDecimal,
    val side: String,
)

object SettlementCalculator {
    private val FEE_RATE      = BigDecimal("0.00015")  // 0.015% — 온라인 위탁
    private val SELL_TAX_RATE = BigDecimal("0.0018")   // 0.18% — 증권거래세 + 농특세

    fun calculate(side: String, quantity: Int, fillPrice: BigDecimal): SettlementCalculation {
        val grossAmount = fillPrice.multiply(BigDecimal(quantity)).setScale(4, RoundingMode.HALF_UP)
        val fee = grossAmount.multiply(FEE_RATE).setScale(0, RoundingMode.UP)
        val tax = if (side == "SELL") grossAmount.multiply(SELL_TAX_RATE).setScale(0, RoundingMode.UP)
                  else BigDecimal.ZERO

        // BUY: 매수금액 + 수수료  /  SELL: 매도금액 - 수수료 - 세금
        val netAmount = if (side == "BUY") grossAmount.add(fee)
                        else grossAmount.subtract(fee).subtract(tax)

        return SettlementCalculation(grossAmount, fee, tax, netAmount, side)
    }
}
