package com.monticker.api.risk

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.risk.application.RiskCheckAuditLogger
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.risk.application.RiskLimitService
import com.monticker.api.risk.application.RiskRuleQueryService
import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.risk.infrastructure.RiskLimitRepository
import com.monticker.api.support.PostgresIntegrationTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

/**
 * ADR-092 — 실제 Postgres에서 미리보기가 risk_check_logs에 한 줄도 남기지 않는지 확인한다(같은 입력의 dryRun은 남긴다 —
 * 테스트가 "아무것도 안 해서 통과"하지 않게 대조군으로 둔다). 실제 규칙 SQL(보유·VaR·시간당 주문)을 모두 탄다.
 */
class RiskPreviewNoAuditIntegrationTest : PostgresIntegrationTest() {

    private val limitRepo = mockk<RiskLimitRepository>()
    private val registry = SimpleMeterRegistry()
    private val service = RiskCheckerService(
        RiskLimitService(limitRepo, jdbcTemplate),
        RiskRuleQueryService(jdbcTemplate),
        RiskCheckAuditLogger(jdbcTemplate, ObjectMapper()),
        registry,
        jdbcTemplate,
    )

    private fun newUser(): Long {
        val id = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "preview-${System.nanoTime()}@test.local", "preview",
        )!!
        jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash) VALUES (?, ?)", id, BigDecimal("10000000"))
        every { limitRepo.findByUserId(id) } returns Optional.of(RiskLimit(userId = id))
        return id
    }

    private fun logRows(userId: Long) =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_check_logs WHERE user_id = ?", Long::class.java, userId)!!

    @Test
    fun `preview leaves no audit row while the same dry run does`() {
        val userId = newUser()
        val stockId = jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id LIMIT 1", Long::class.java)!!

        val blocked = service.preview(userId, stockId, "BUY", 1_000, BigDecimal("100000"))   // 1억 — 집중도 초과
        val approved = service.preview(userId, stockId, "BUY", 1, BigDecimal("100"))

        assertThat(blocked.approved).isFalse()
        assertThat(approved.approved).isTrue()
        assertThat(logRows(userId)).isZero()
        assertThat(registry.find("risk_check_total").counters()).isEmpty()

        val dry = service.dryRun(userId, stockId, "BUY", 1_000, BigDecimal("100000"))
        assertThat(dry).isEqualTo(blocked)
        assertThat(logRows(userId)).isEqualTo(1L)
    }
}
