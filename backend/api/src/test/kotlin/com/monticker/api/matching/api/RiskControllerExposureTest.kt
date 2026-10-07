package com.monticker.api.matching.api

import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.risk.application.RiskLimitService
import com.monticker.api.risk.application.RiskLimitsView
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal

class RiskControllerExposureTest {

    private val userId = 7L
    private val limitService = mockk<RiskLimitService>()
    private val orderRepo = mockk<OrderRepository>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val riskChecker = mockk<RiskCheckerService>()
    private val controller = RiskController(limitService, riskChecker, orderRepo, jdbc)

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
        every { limitService.view(userId) } returns RiskLimitsView(RiskLimit(userId = userId), emptyList())
        every { jdbc.queryForObject(match<String> { it.contains("paper_accounts") }, BigDecimal::class.java, userId) } returns BigDecimal("7385871")
        every { jdbc.queryForList(match<String> { it.contains("paper_trades") }, userId) } returns emptyList()
        // 이전 구현이 읽던 '오늘 체결 현금 흐름' — 매수만 했으면 음수가 된다. 이 값이 손익으로 새면 안 된다.
        every { jdbc.queryForObject(match<String> { it.contains("FROM fills") }, BigDecimal::class.java, userId) } returns BigDecimal("-2614129")
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `daily pnl is today's realized pnl from the risk gate, so buys are not shown as a loss`() {
        every { riskChecker.paperRealizedPnlToday(userId) } returns BigDecimal.ZERO

        val body = controller.getCurrentExposure().body!!

        assertThat(body.dailyPnl).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(body.dailyPnlPct).isEqualTo(0.0)
    }

    @Test
    fun `a realized loss today is reported as is`() {
        every { riskChecker.paperRealizedPnlToday(userId) } returns BigDecimal("-73859")

        val body = controller.getCurrentExposure().body!!

        assertThat(body.dailyPnl).isEqualByComparingTo(BigDecimal("-73859"))
        assertThat(body.dailyPnlPct).isEqualTo(-1.0)
    }
}
