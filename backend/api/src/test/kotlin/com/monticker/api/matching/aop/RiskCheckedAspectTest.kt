package com.monticker.api.matching.aop

import com.monticker.api.common.aop.RiskChecked
import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.matching.application.RiskCheckerService
import com.monticker.api.matching.application.RiskCheckResult
import com.monticker.api.matching.application.RuleResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import java.math.BigDecimal

class RiskCheckedAspectTest {

    private val riskChecker = mockk<RiskCheckerService>()
    private val aspect = RiskCheckedAspect(riskChecker)

    private lateinit var proxy: SampleOrderService

    @BeforeEach
    fun setUp() {
        val factory = AspectJProxyFactory(SampleOrderService())
        factory.addAspect(aspect)
        proxy = factory.getProxy()
    }

    @Test
    fun `@RiskChecked — 리스크 통과 시 메서드 실행`() {
        every { riskChecker.check(1L, 10L, "BUY", 5, BigDecimal("70000")) } returns approved()

        val result = proxy.placeOrder(userId = 1L, stockId = 10L, side = "BUY", quantity = 5, estimatedPrice = BigDecimal("70000"))

        assertThat(result).isEqualTo("OK")
        verify(exactly = 1) { riskChecker.check(1L, 10L, "BUY", 5, BigDecimal("70000")) }
    }

    @Test
    fun `@RiskChecked — 리스크 차단 시 RiskLimitException 발생`() {
        every { riskChecker.check(1L, 10L, "BUY", 5, any()) } returns blocked("DailyLossRule")

        assertThatThrownBy { proxy.placeOrder(userId = 1L, stockId = 10L, side = "BUY", quantity = 5, estimatedPrice = BigDecimal("70000")) }
            .isInstanceOf(RiskLimitException::class.java)
            .hasMessageContaining("DailyLossRule")
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────────

    open class SampleOrderService {
        @RiskChecked
        open fun placeOrder(userId: Long, stockId: Long, side: String, quantity: Int, estimatedPrice: BigDecimal): String = "OK"
    }

    private fun approved() = RiskCheckResult(
        approved  = true,
        blockedBy = null,
        severity  = "NONE",
        checks    = emptyList(),
    )

    private fun blocked(rule: String) = RiskCheckResult(
        approved  = false,
        blockedBy = rule,
        severity  = "BLOCK",
        checks    = listOf(RuleResult(rule, false, "초과", 100.0, 50.0)),
    )
}
