package com.monticker.api.brokerage.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** 정산 행의 수수료·세금 식을 고정한다 — 정산(createSettlementFromFill)과 리밸런싱 미리보기가 같은 값을 써야 한다. */
class BrokerageFeeModelTest {

    @Test
    fun `fee is 0_015 percent rounded up to the won`() {
        assertThat(BrokerageFeeModel.fee(BigDecimal("1000000"))).isEqualByComparingTo("150")
        assertThat(BrokerageFeeModel.fee(BigDecimal("333"))).isEqualByComparingTo("1")      // 0.04995 → 1
        assertThat(BrokerageFeeModel.fee(BigDecimal.ZERO)).isEqualByComparingTo("0")
    }

    @Test
    fun `sell tax is 0_18 percent rounded up, buys pay none`() {
        assertThat(BrokerageFeeModel.tax(OrderSide.SELL, BigDecimal("1000000"))).isEqualByComparingTo("1800")
        assertThat(BrokerageFeeModel.tax(OrderSide.SELL, BigDecimal("333"))).isEqualByComparingTo("1")   // 0.5994 → 1
        assertThat(BrokerageFeeModel.tax(OrderSide.BUY, BigDecimal("1000000"))).isEqualByComparingTo("0")
    }
}
