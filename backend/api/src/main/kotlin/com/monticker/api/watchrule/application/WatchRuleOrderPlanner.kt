package com.monticker.api.watchrule.application

import com.monticker.api.common.domain.CandleFreshness
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleOrderType
import com.monticker.api.watchrule.domain.WatchRuleSizeType
import com.monticker.api.watchrule.domain.WatchRuleSizing
import com.monticker.api.wallet.equity.PaperEquityQuery
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Clock
import java.util.Locale

/** 발동 시점에 정한 주문 — 또는 주문하지 않는 이유. */
sealed interface OrderPlan {
    /** [limitPrice]가 있으면 지정가, 없으면 시장가. */
    data class Ready(val quantity: Int, val limitPrice: BigDecimal?) : OrderPlan
    data class Skip(val reason: String) : OrderPlan
}

/**
 * ADR-095 — 발동 시점에 주문 수량·지정가를 정한다. [WatchRuleExecutor]가 발동권(ADR-077 claim)을 잡은 **뒤에** 부른다 —
 * 평가자산은 발동 시점 값이어야 하고, Skip이면 실행기가 발동권을 돌려준다.
 *
 * - 시장가 + 주 수(기존 규칙): 시세를 읽지 않는다 — 신선도 판정은 예전처럼 사가가 한다.
 * - 지정가 또는 계좌 %: 발동 가격(최신 1분봉 종가)이 필요하다. 없거나 [CandleFreshness.MAX_AGE]보다 오래되면 건너뛴다 —
 *   오래된 가격으로 지정가·수량을 정하면 사용자가 선언한 의도("발동 시점 가격 대비")와 다른 주문이 된다.
 */
@Component
class WatchRuleOrderPlanner(
    private val targets: WatchRuleTargets,
    private val equity: PaperEquityQuery,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun plan(rule: WatchRule, stockId: Long): OrderPlan {
        val isLimit = rule.orderType == WatchRuleOrderType.LIMIT
        val isPct = rule.sizeType == WatchRuleSizeType.EQUITY_PCT
        if (!isLimit && !isPct) return OrderPlan.Ready(rule.quantity!!, null)

        val latest = targets.latestPrice(stockId)
            ?: return OrderPlan.Skip("발동 시점 시세 없음 — 지정가·계좌 비율을 계산할 수 없습니다")
        if (!CandleFreshness.isFresh(latest.candleTime, clock.instant())) {
            return OrderPlan.Skip("발동 시점 시세가 ${CandleFreshness.MAX_AGE.toMinutes()}분 넘게 갱신되지 않음(마지막 ${latest.candleTime})")
        }

        val limit = if (isLimit) WatchRuleSizing.limitPrice(latest.close, rule.limitOffsetBps!!, rule.side) else null
        val quantity = if (isPct) {
            val pct = rule.equityPct!!
            val unit = limit ?: latest.close
            val eq = equity.paperEquity(rule.userId)
            val qty = WatchRuleSizing.quantityForEquity(eq, pct, unit)
            if (qty <= 0) {
                return OrderPlan.Skip(
                    "계좌 평가자산 ${won(eq)}의 ${pct.stripTrailingZeros().toPlainString()}%(${won(WatchRuleSizing.budget(eq, pct))})로는 " +
                        "1주(${won(unit)})도 살 수 없어 0주 — 주문하지 않음",
                )
            }
            qty
        } else rule.quantity!!
        return OrderPlan.Ready(quantity, limit)
    }

    private fun won(v: BigDecimal): String = NumberFormat.getNumberInstance(Locale.KOREA).format(v) + "원"
}
