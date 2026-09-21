package com.monticker.api.risk.aop

import com.monticker.api.common.aop.RiskChecked
import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RuleResult
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
    private lateinit var marketProxy: MarketOrderService

    @BeforeEach
    fun setUp() {
        proxy = AspectJProxyFactory(SampleOrderService()).also { it.addAspect(aspect) }.getProxy()
        marketProxy = AspectJProxyFactory(MarketOrderService()).also { it.addAspect(aspect) }.getProxy()
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

    // 5c53b2b 회귀 — MatchingService.submitMarket(userId, stockId, side, quantity)처럼 가격 파라미터가 없는
    // 시장가 진입점. 어스펙트는 ZERO를 "가격 불명" 신호로 넘기고, 최근가 치환은 RiskCheckerService.check가 맡는다.
    // 게이트가 최근가로 승인하면 메서드가 실행돼야 한다.
    @Test
    fun `@RiskChecked — 가격 파라미터가 없는 MARKET 주문은 ZERO 신호로 판정을 위임하고 통과 시 실행`() {
        every { riskChecker.check(1L, 2L, "BUY", 11, BigDecimal.ZERO) } returns approved()

        val result = marketProxy.submitMarket(userId = 1L, stockId = 2L, side = "BUY", quantity = 11)

        assertThat(result).isEqualTo("FILLED")
        verify(exactly = 1) { riskChecker.check(1L, 2L, "BUY", 11, BigDecimal.ZERO) }
    }

    @Test
    fun `@RiskChecked — MARKET 주문도 최근가까지 없어 차단되면 RiskLimitException`() {
        every { riskChecker.check(1L, 2L, "BUY", 11, BigDecimal.ZERO) } returns blocked("ConcentrationRule")

        assertThatThrownBy { marketProxy.submitMarket(userId = 1L, stockId = 2L, side = "BUY", quantity = 11) }
            .isInstanceOf(RiskLimitException::class.java)
            .hasMessageContaining("ConcentrationRule")
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────────

    open class SampleOrderService {
        @RiskChecked
        open fun placeOrder(userId: Long, stockId: Long, side: String, quantity: Int, estimatedPrice: BigDecimal): String = "OK"
    }

    /** MatchingService.submitMarket과 같은 시그니처 — 가격 파라미터 없음. */
    open class MarketOrderService {
        @RiskChecked
        open fun submitMarket(userId: Long, stockId: Long, side: String, quantity: Int): String = "FILLED"
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
