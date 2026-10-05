package com.monticker.api.risk.application

import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.risk.domain.RiskLimit
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

class RiskLimitNearWarningJobTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val usage = mockk<PaperRiskUsage>()
    private val repo = mockk<RiskLimitService>()
    private val tx = TransactionTemplate(mockk<PlatformTransactionManager>(relaxed = true))
    private val job = RiskLimitNearWarningJob(jdbc, tx, events, usage, repo, SimpleMeterRegistry())

    private val userId = 7L
    private val today = LocalDate.of(2026, 10, 5)
    private val limits = RiskLimit(userId = userId)

    private fun stubInsert(result: Int) {
        every { jdbc.update(match<String> { it.contains("INSERT INTO risk_limit_warnings") }, *anyVararg()) } returns result
    }

    @Test
    fun `80퍼센트를 넘은 규칙만 하루 키로 알린다`() {
        every { repo.effective(userId) } returns limits
        every { usage.evaluate(userId, limits) } returns listOf(
            RuleUsage("VAR", "1일 VaR", 4.3, 5.0),            // 86% — 알림
            RuleUsage("CONCENTRATION", "단일 종목 집중도", 20.0, 30.0, "005930"), // 67% — 아님
        )
        stubInsert(1)

        val sent = job.evaluateUser(userId, today)

        assertThat(sent).isEqualTo(1)
        verify(exactly = 1) {
            events.publishEvent(match<Any> {
                it is UserNotificationCommand && it.userId == userId && it.dedupKey == "risk-limit-near:7:VAR:2026-10-05" &&
                    it.data["type"] == "RISK_LIMIT_NEAR"
            })
        }
    }

    @Test
    fun `오늘 이미 알린 규칙은 다시 알리지 않는다`() {
        every { repo.effective(userId) } returns limits
        every { usage.evaluate(userId, limits) } returns listOf(RuleUsage("DAILY_LOSS", "일일 손실", 2.9, 3.0))
        stubInsert(0) // ON CONFLICT DO NOTHING — 다른 주기나 다른 인스턴스가 먼저 넣었다

        assertThat(job.evaluateUser(userId, today)).isEqualTo(0)
        verify(exactly = 0) { events.publishEvent(any<Any>()) }
    }

    @Test
    fun `리스크 체크를 끈 사용자는 평가하지 않는다`() {
        every { repo.effective(userId) } returns RiskLimit(userId = userId, isActive = false)

        assertThat(job.evaluateUser(userId, today)).isEqualTo(0)
        verify(exactly = 0) { usage.evaluate(any(), any()) }
    }

    @Test
    fun `한도 미만이면 쓰지도 알리지도 않는다`() {
        every { repo.effective(userId) } returns limits
        every { usage.evaluate(userId, limits) } returns listOf(RuleUsage("VAR", "1일 VaR", 3.9, 5.0)) // 78%

        assertThat(job.evaluateUser(userId, today)).isEqualTo(0)
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("risk_limit_warnings") }, *anyVararg()) }
    }

    @Test
    fun `한 사용자의 평가 실패가 다른 사용자를 막지 않는다`() {
        every { jdbc.queryForList(match<String> { it.contains("paper_trades") }, Long::class.java, any()) } returns listOf(1L, 2L)
        every { repo.effective(1L) } throws IllegalStateException("boom")
        val l2 = RiskLimit(userId = 2L)
        every { repo.effective(2L) } returns l2
        every { usage.evaluate(2L, l2) } returns listOf(RuleUsage("VAR", "1일 VaR", 6.0, 5.0))
        stubInsert(1)

        job.run()

        verify(exactly = 1) { events.publishEvent(match<Any> { it is UserNotificationCommand && it.userId == 2L }) }
    }
}
