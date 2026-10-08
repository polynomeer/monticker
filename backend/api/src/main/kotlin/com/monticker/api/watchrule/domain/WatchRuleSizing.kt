package com.monticker.api.watchrule.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ADR-095 — 발동 주문의 지정가·수량 계산. 순수 함수(DB 없이 단위 테스트).
 *
 * - 지정가 = 발동 가격 × (1 + 오프셋 bps / 10,000). 반올림은 **요청보다 공격적이지 않은 쪽**: 매수는 내림, 매도는 올림.
 *   자릿수는 발동 가격의 자릿수(최대 4 — `orders.limit_price NUMERIC(18,4)`)를 따른다. 원화 종목이면 원 단위다.
 *   호가 단위는 맞추지 않는다 — 모의투자 지정가에는 호가 단위 검증이 없다(ADR-074 §6).
 * - 비율 수량 = ⌊평가자산 × 비율% ÷ 단가⌋. 단가는 지정가 규칙이면 지정가, 시장가 규칙이면 발동 가격. 0주면 발동하지 않는다.
 */
object WatchRuleSizing {
    /** 지정가 오프셋 허용 범위(bps) — ±10%. 국내 가격제한폭(±30%) 안쪽이고, 넘으면 의도와 다른 주문일 가능성이 크다. */
    val OFFSET_BPS_RANGE: IntRange = -1000..1000

    /** 평가자산 대비 비율 허용 범위(%). 한 번의 자동 발동이 계좌의 4분의 1을 넘지 않게 한다. */
    val EQUITY_PCT_MIN: BigDecimal = BigDecimal.ONE
    val EQUITY_PCT_MAX: BigDecimal = BigDecimal("25")

    private const val MAX_PRICE_SCALE = 4
    private val BPS_DENOMINATOR = BigDecimal("10000")
    private val HUNDRED = BigDecimal("100")

    fun requireOffset(bps: Int) =
        require(bps in OFFSET_BPS_RANGE) { "지정가 오프셋은 ${OFFSET_BPS_RANGE.first}~${OFFSET_BPS_RANGE.last}bp(±10%)여야 합니다" }

    fun requireEquityPct(pct: BigDecimal) {
        require(pct >= EQUITY_PCT_MIN && pct <= EQUITY_PCT_MAX) { "계좌 비율은 1~25%여야 합니다" }
        require(pct.stripTrailingZeros().scale() <= 2) { "계좌 비율은 소수점 둘째 자리까지입니다" }
    }

    /** 발동 가격 [reference]에 [bps]를 더한 지정가. 결과가 0 이하면(극단적인 저가 종목) IllegalArgumentException. */
    fun limitPrice(reference: BigDecimal, bps: Int, side: WatchRuleSide): BigDecimal {
        require(reference > BigDecimal.ZERO) { "발동 가격이 0 이하입니다" }
        requireOffset(bps)
        val scale = reference.stripTrailingZeros().scale().coerceIn(0, MAX_PRICE_SCALE)
        val raw = reference.multiply(BigDecimal.ONE + BigDecimal(bps).divide(BPS_DENOMINATOR))
        val rounded = raw.setScale(scale, if (side == WatchRuleSide.BUY) RoundingMode.FLOOR else RoundingMode.CEILING)
        require(rounded > BigDecimal.ZERO) { "지정가가 0 이하로 계산됐습니다(발동 가격 $reference, 오프셋 ${bps}bp)" }
        return rounded
    }

    /** ⌊[equity] × [pct]% ÷ [unitPrice]⌋. 평가자산이 0 이하이거나 단가보다 작으면 0. */
    fun quantityForEquity(equity: BigDecimal, pct: BigDecimal, unitPrice: BigDecimal): Int {
        require(unitPrice > BigDecimal.ZERO) { "단가가 0 이하입니다" }
        if (equity <= BigDecimal.ZERO) return 0
        val budget = equity.multiply(pct).divide(HUNDRED)
        val shares = budget.divide(unitPrice, 0, RoundingMode.FLOOR)
        return shares.min(BigDecimal(Int.MAX_VALUE)).toInt()
    }

    /** 비율 수량의 금액 상한 — 0주로 건너뛸 때 기록할 사유에 쓴다. */
    fun budget(equity: BigDecimal, pct: BigDecimal): BigDecimal =
        equity.multiply(pct).divide(HUNDRED).setScale(0, RoundingMode.FLOOR)
}
