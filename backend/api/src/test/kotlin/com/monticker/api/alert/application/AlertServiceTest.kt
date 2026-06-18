package com.monticker.api.alert.application

import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class AlertServiceTest {

    private val repo = mockk<AlertRuleRepository>()
    private val service = AlertService(repo, ObjectMapper())

    @Test
    fun `createRule saves and returns rule`() {
        val rule = AlertRule(
            id = 1L, userId = 1L, stockId = 1L,
            ruleType = AlertRuleType.PRICE_ABOVE,
            conditionJson = """{"threshold":75000}""",
        )
        every { repo.save(any()) } returns rule

        val result = service.createRule(1L, 1L, AlertRuleType.PRICE_ABOVE, mapOf("threshold" to 75000))

        assertThat(result.ruleType).isEqualTo(AlertRuleType.PRICE_ABOVE)
        verify { repo.save(any()) }
    }

    @Test
    fun `deactivateRule throws when rule not found`() {
        every { repo.findById(99L) } returns Optional.empty()

        assertThatThrownBy { service.deactivateRule(1L, 99L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `deactivateRule throws on ownership mismatch`() {
        val rule = AlertRule(
            id = 1L, userId = 2L, stockId = null,
            ruleType = AlertRuleType.VOLUME_SURGE,
            conditionJson = "{}",
        )
        every { repo.findById(1L) } returns Optional.of(rule)

        assertThatThrownBy { service.deactivateRule(1L, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
