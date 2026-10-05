package com.monticker.api.brokerage.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class KrxPriceRulesTest {

    @ParameterizedTest
    @CsvSource(
        "1999, 1", "2000, 5", "4995, 5", "5000, 10", "19990, 10", "20000, 50", "49950, 50",
        "50000, 100", "199900, 100", "200000, 500", "499500, 500", "500000, 1000", "1500000, 1000",
    )
    fun `호가 단위는 2023 개편 7단계 구간을 따른다`(price: String, tick: Int) {
        assertThat(KrxPriceRules.tickSize(BigDecimal(price))).isEqualByComparingTo(BigDecimal(tick))
    }

    @ParameterizedTest
    @CsvSource(
        "70000, true", "70100, true", "70050, false", "70000.00, true", "70000.5, false",
        "1999, true", "2001, false", "2005, true", "0, false", "-100, false",
    )
    fun `호가 단위 배수인 원 단위 양수만 통과한다`(price: String, ok: Boolean) {
        assertThat(KrxPriceRules.isOnTick(BigDecimal(price))).isEqualTo(ok)
    }

    @Test
    fun `가격제한폭은 기준가 ±30%의 바깥 경계다`() {
        val band = KrxPriceRules.band(BigDecimal("70000"))
        assertThat(band.start).isEqualByComparingTo("49000")
        assertThat(band.endInclusive).isEqualByComparingTo("91000")
        assertThat(BigDecimal("91000") in band).isTrue()
        assertThat(BigDecimal("91100") in band).isFalse()
        assertThat(BigDecimal("48900") in band).isFalse()
    }

    @Test
    fun `KRX 시장만 대상이다`() {
        assertThat(KrxPriceRules.isKrxMarket("KOSPI")).isTrue()
        assertThat(KrxPriceRules.isKrxMarket("KOSDAQ")).isTrue()
        assertThat(KrxPriceRules.isKrxMarket("NASDAQ")).isFalse()
        assertThat(KrxPriceRules.isKrxMarket(null)).isFalse()
    }
}
