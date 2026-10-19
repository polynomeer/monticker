package com.monticker.api.portfolio.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** 한 계좌 안의 한 종목 포지션(거래 원장으로 계산) */
data class Position(
    val key: InstrumentKey,
    val name: String?,
    val currency: String,
    val quantity: BigDecimal,
    /** 이동평균 매입 원가 합계 — 매수 수수료 포함 */
    val costBasis: BigDecimal,
    val realizedPnl: BigDecimal,
    /** 받은 배당(원천징수 세금 뺀 금액) */
    val dividendsNet: BigDecimal,
    /** 가진 것보다 많이 판 기록이 있다 — 앞 구간 거래가 빠졌을 가능성(CSV 누락 등). 확인이 필요하다 */
    val oversold: Boolean,
) {
    val isOpen get() = quantity.signum() > 0

    /** 주당 평균 매입 원가 */
    val avgCost: BigDecimal?
        get() = if (isOpen) costBasis.divide(quantity, 6, RoundingMode.HALF_UP) else null
}

data class CalculatedAccount(
    val positions: List<Position>,
    /** 통화별 현금 증감 합계 — 입출금까지 기록했을 때만 실제 예수금과 맞는다 */
    val cashByCurrency: Map<String, BigDecimal>,
)

/**
 * 거래 원장 → 포지션. 순수 함수다(ADR-106: 보유는 원장으로 계산한다).
 *
 * - 매입 원가는 **이동평균**이다. 매수 수수료를 원가에 넣는다. 국내 증권사 앱의 "평균단가"는 대개 이동평균이고
 *   수수료 포함 여부는 증권사마다 다르다 — 화면에 기준을 밝힌다.
 * - 매도는 그때의 평균 원가로 실현손익을 낸다(매도 수수료·세금 차감).
 * - 가진 것보다 많이 팔면 예외를 던지지 않는다. 가진 만큼만 계산하고 [Position.oversold]로 표시한다 — 파일로 가져온
 *   기록은 앞 구간이 빠지기 쉽고, 한 행 때문에 계좌 전체를 못 보여주면 안 된다.
 * - 거래는 날짜, 같은 날이면 입력 순(id)으로 처리한다.
 */
object HoldingsCalculator {

    private class Acc(val key: InstrumentKey, var name: String?, val currency: String) {
        var quantity: BigDecimal = BigDecimal.ZERO
        var cost: BigDecimal = BigDecimal.ZERO
        var realized: BigDecimal = BigDecimal.ZERO
        var dividends: BigDecimal = BigDecimal.ZERO
        var oversold = false
    }

    fun calculate(transactions: List<PortfolioTransaction>): CalculatedAccount {
        val positions = LinkedHashMap<Pair<InstrumentKey, String>, Acc>()
        val cash = LinkedHashMap<String, BigDecimal>()
        fun cashAdd(currency: String, delta: BigDecimal) { cash[currency] = (cash[currency] ?: BigDecimal.ZERO) + delta }

        for (tx in transactions.sortedWith(compareBy({ it.tradeDate }, { it.id }))) {
            val acc = tx.instrumentKey?.let { key ->
                positions.getOrPut(key to tx.currency) { Acc(key, tx.instrumentName, tx.currency) }
                    .also { if (it.name == null) it.name = tx.instrumentName }
            }
            when (tx.type) {
                TransactionType.BUY -> {
                    val a = acc ?: error("매수에는 종목이 필요하다(DB 제약 ck_portfolio_tx_trade)")
                    a.quantity += tx.quantity!!
                    a.cost += tx.amount + tx.fee
                    cashAdd(tx.currency, -(tx.amount + tx.fee + tx.tax))
                }
                TransactionType.SELL -> {
                    val a = acc ?: error("매도에는 종목이 필요하다(DB 제약 ck_portfolio_tx_trade)")
                    val requested = tx.quantity!!
                    val sold = requested.min(a.quantity)
                    if (requested > a.quantity) a.oversold = true
                    if (sold.signum() > 0) {
                        val costOut = a.cost.multiply(sold).divide(a.quantity, 10, RoundingMode.HALF_UP)
                        // 가진 만큼의 비율로만 매도 금액·비용을 나눈다(초과분은 원가를 모르는 매도다)
                        val ratio = sold.divide(requested, 10, RoundingMode.HALF_UP)
                        val proceeds = (tx.amount - tx.fee - tx.tax).multiply(ratio)
                        a.realized += proceeds - costOut
                        a.cost -= costOut
                        a.quantity -= sold
                        if (a.quantity.signum() == 0) a.cost = BigDecimal.ZERO
                    }
                    cashAdd(tx.currency, tx.amount - tx.fee - tx.tax)
                }
                TransactionType.DIVIDEND -> {
                    acc?.let { it.dividends += tx.amount - tx.tax }
                    cashAdd(tx.currency, tx.amount - tx.tax - tx.fee)
                }
                TransactionType.DEPOSIT -> cashAdd(tx.currency, tx.amount)
                TransactionType.WITHDRAWAL -> cashAdd(tx.currency, -tx.amount)
                TransactionType.FEE, TransactionType.TAX -> cashAdd(tx.currency, -(tx.amount + tx.fee + tx.tax))
            }
        }

        return CalculatedAccount(
            positions = positions.values.map {
                Position(
                    key = it.key, name = it.name, currency = it.currency,
                    quantity = it.quantity.stripTrailingZeros().let { q -> if (q.scale() < 0) q.setScale(0) else q },
                    costBasis = it.cost.setScale(4, RoundingMode.HALF_UP),
                    realizedPnl = it.realized.setScale(4, RoundingMode.HALF_UP),
                    dividendsNet = it.dividends.setScale(4, RoundingMode.HALF_UP),
                    oversold = it.oversold,
                )
            },
            cashByCurrency = cash.mapValues { it.value.setScale(4, RoundingMode.HALF_UP) },
        )
    }
}
