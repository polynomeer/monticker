package com.monticker.api.portfolio.domain.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** XIRR — 기대값은 엑셀 XIRR 문서 예제와, 같은 정의로 따로 푼 값(이분법)이다 */
class XirrTest {

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `엑셀 XIRR 문서 예제와 같다 — 37_34퍼센트`() {
        val r = Xirr.of(listOf(
            CashFlow(d("2008-01-01"), -10000.0), CashFlow(d("2008-03-01"), 2750.0), CashFlow(d("2008-10-30"), 4250.0),
            CashFlow(d("2009-02-15"), 3250.0), CashFlow(d("2009-04-01"), 2750.0),
        ))
        assertThat(r).isCloseTo(0.3733625335, within(1e-6))
    }

    @Test
    fun `손실도 낸다 — 1년 뒤 절반이면 -50퍼센트`() {
        assertThat(Xirr.of(listOf(CashFlow(d("2025-01-01"), -1000.0), CashFlow(d("2026-01-01"), 500.0)))).isCloseTo(-0.5, within(1e-6))
    }

    @Test
    fun `순서가 섞여 있어도 날짜로 정렬해 푼다`() {
        val r = Xirr.of(listOf(CashFlow(d("2026-10-19"), 800000.0), CashFlow(d("2025-01-02"), -700100.0), CashFlow(d("2026-04-15"), 846.0)))
        assertThat(r).isCloseTo(0.0778227623, within(1e-6))
    }

    @Test
    fun `답이 없으면 null — 넣기만 했거나, 30일보다 짧다`() {
        assertThat(Xirr.of(listOf(CashFlow(d("2025-01-01"), -1000.0), CashFlow(d("2025-06-01"), -500.0)))).isNull()
        assertThat(Xirr.of(listOf(CashFlow(d("2025-01-01"), -1000.0), CashFlow(d("2025-01-20"), 1100.0)))).isNull()
        assertThat(Xirr.of(listOf(CashFlow(d("2025-01-01"), -1000.0)))).isNull()
    }
}
