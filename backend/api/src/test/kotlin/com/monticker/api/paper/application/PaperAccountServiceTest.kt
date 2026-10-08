package com.monticker.api.paper.application

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.paper.domain.PaperInitialCapital
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

/** ADR-089 — 시작 자금 화이트리스트, 첫 생성만, 같은 값 재요청은 멱등, 다른 값은 409(잔고 불변). */
class PaperAccountServiceTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val service = PaperAccountService(jdbc)

    private fun existing(initial: String, cash: String) {
        every { jdbc.query(match<String> { it.startsWith("SELECT initial_capital") }, any<RowMapper<PaperAccountResponse>>(), 7L) } returns
            listOf(PaperAccountResponse(BigDecimal(initial), BigDecimal(cash), created = false))
    }

    @ParameterizedTest
    @ValueSource(strings = ["10000000", "30000000", "100000000", "30000000.0000"])
    fun `whitelisted capitals are accepted`(v: String) {
        assertThat(PaperInitialCapital.parse(BigDecimal(v)).amount).isEqualByComparingTo(v)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-10000000", "1", "20000000", "30000000.5", "1000000000", "99999999999999"])
    fun `non-whitelisted capitals are rejected with 400 and nothing is written`(v: String) {
        assertThatThrownBy { service.open(7L, BigDecimal(v)) }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `missing capital is rejected`() {
        assertThatThrownBy { service.open(7L, null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `first creation inserts the chosen capital as both cash and initial capital`() {
        every { jdbc.update(match<String> { it.contains("ON CONFLICT (user_id) DO NOTHING") }, 7L, BigDecimal("30000000"), BigDecimal("30000000")) } returns 1
        existing("30000000", "30000000")

        val r = service.open(7L, BigDecimal("30000000"))

        assertThat(r.created).isTrue()
        assertThat(r.cash).isEqualByComparingTo("30000000")
    }

    @Test
    fun `same capital again is idempotent and does not touch the balance`() {
        every { jdbc.update(any<String>(), *anyVararg()) } returns 0
        existing("100000000", "87000000")   // 거래로 줄어든 잔고 — 그대로 돌려줘야 한다

        val r = service.open(7L, BigDecimal("100000000"))

        assertThat(r.created).isFalse()
        assertThat(r.cash).isEqualByComparingTo("87000000")
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("UPDATE") }, *anyVararg()) }
    }

    @Test
    fun `different capital on an existing account is a 409 and never resets the balance`() {
        every { jdbc.update(any<String>(), *anyVararg()) } returns 0
        existing("10000000", "4200000")

        assertThatThrownBy { service.open(7L, BigDecimal("100000000")) }
            .isInstanceOf(BusinessRuleException::class.java)
            .hasMessageContaining("10,000,000")
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("UPDATE") }, *anyVararg()) }
    }
}
