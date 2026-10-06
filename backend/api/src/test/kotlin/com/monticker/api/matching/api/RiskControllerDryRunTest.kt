package com.monticker.api.matching.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.risk.application.RiskLimitService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal

/** 보안 리뷰 — POST /api/risk/check(사전 점검)의 입력 검증·호출 제한·감사 구분. */
class RiskControllerDryRunTest {
    private val userId = 7L
    private val riskChecker = mockk<RiskCheckerService>()
    private val controller = RiskController(mockk<RiskLimitService>(), riskChecker, mockk<OrderRepository>(), mockk<JdbcTemplate>())

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
        every { riskChecker.dryRun(any(), any(), any(), any(), any()) } returns
            RiskCheckResult(approved = true, blockedBy = null, severity = "APPROVED", checks = emptyList())
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `runs the gate as a dry run`() {
        controller.dryRunCheck(DryRunCheckRequest(stockId = 1L, side = "BUY", quantity = 3, estimatedPrice = BigDecimal("1000")))
        verify { riskChecker.dryRun(userId, 1L, "BUY", 3, BigDecimal("1000")) }
    }

    @Test
    fun `rejects a side other than BUY or SELL`() {
        assertThatThrownBy { controller.dryRunCheck(DryRunCheckRequest(1L, "x".repeat(40), 1, BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { riskChecker.dryRun(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejects a non-positive quantity`() {
        assertThatThrownBy { controller.dryRunCheck(DryRunCheckRequest(1L, "SELL", 0, BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { riskChecker.dryRun(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `is rate limited`() {
        val m = RiskController::class.java.methods.first { it.name == "dryRunCheck" }
        assertThat(m.getAnnotation(RateLimited::class.java)).isNotNull()
    }
}
