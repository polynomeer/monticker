package com.monticker.api.brokerage.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class RiskPreviewOrderRequestTest {

    private fun req(
        symbol: String? = "005930", side: String? = "BUY", quantity: String? = "10", limitPrice: String? = null,
    ) = RiskPreviewOrderRequest(symbol, side, quantity?.let(::BigDecimal), limitPrice?.let(::BigDecimal))

    @Test
    fun `정상 입력을 그대로 넘긴다`() {
        val v = req(limitPrice = "70000").validated()
        assertThat(v.symbol).isEqualTo("005930")
        assertThat(v.quantity).isEqualTo(10)
        assertThat(v.limitPrice).isEqualByComparingTo("70000")
    }

    @Test
    fun `소수 수량을 잘라 넣지 않고 거부한다`() {
        assertThrows<IllegalArgumentException> { req(quantity = "1.5").validated() }
    }

    @Test
    fun `10_0 같은 정수 표기는 받는다`() {
        assertThat(req(quantity = "10.0").validated().quantity).isEqualTo(10)
    }

    @Test
    fun `잘못된 값을 거부한다`() {
        listOf(
            req(symbol = null), req(symbol = " "), req(symbol = "A".repeat(33)),
            req(side = "buy"), req(side = null),
            req(quantity = "0"), req(quantity = "-1"), req(quantity = null), req(quantity = "1000001"),
            req(limitPrice = "-1"), req(limitPrice = "1000000000001"),
        ).forEach { r -> assertThrows<IllegalArgumentException>("$r") { r.validated() } }
    }
}
