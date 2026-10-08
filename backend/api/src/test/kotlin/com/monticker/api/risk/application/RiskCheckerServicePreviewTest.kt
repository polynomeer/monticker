package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** ADR-092 — 미리보기는 dryRun과 같은 판정을 내리되 감사 행·메트릭을 남기지 않는다. */
class RiskCheckerServicePreviewTest {
    private val userId = 3L
    private val stockId = 11L
    private val limitService = mockk<RiskLimitService>()
    private val rules = mockk<RiskRuleQueryService>()
    private val audit = mockk<RiskCheckAuditLogger>(relaxed = true)
    private val registry = SimpleMeterRegistry()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = RiskCheckerService(limitService, rules, audit, registry, jdbc)

    private val blocked = listOf(
        RuleResult("QuantityRule", true, "ok", 1.0, 1.0),
        RuleResult("ConcentrationRule", false, "집중도 초과", 45.0, 30.0),
    )

    init {
        every { jdbc.queryForObject(match<String> { it.contains("FROM stocks WHERE id") }, Boolean::class.java, stockId) } returns true
        every { limitService.effective(userId) } returns RiskLimit(userId = userId)
        every { rules.evaluate(userId, stockId, any(), any(), any(), any()) } returns blocked
        every { rules.currentPrice(stockId) } returns BigDecimal("5000")
    }

    @Test
    fun `preview returns the same verdict as dryRun`() {
        val preview = service.preview(userId, stockId, "BUY", 10, BigDecimal("1000"))
        val dry = service.dryRun(userId, stockId, "BUY", 10, BigDecimal("1000"))
        assertThat(preview).isEqualTo(dry)
        assertThat(preview.approved).isFalse()
        assertThat(preview.blockedBy).isEqualTo("ConcentrationRule")
        assertThat(preview.severity).isEqualTo("BLOCKED")
    }

    @Test
    fun `preview writes no audit row and counts no metric`() {
        service.preview(userId, stockId, "SELL", 10, BigDecimal("1000"))
        verify(exactly = 0) { audit.record(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(registry.find("risk_check_total").counters()).isEmpty()
    }

    @Test
    fun `market order preview uses the recent price`() {
        service.preview(userId, stockId, "BUY", 2, BigDecimal.ZERO)
        verify { rules.evaluate(userId, stockId, "BUY", 2, BigDecimal("5000"), any()) }
    }

    @Test
    fun `preview rejects an unknown side before touching the database`() {
        assertThatThrownBy { service.preview(userId, stockId, "HOLD", 1, BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { limitService.effective(any()) }
    }

    @Test
    fun `preview of a missing stock is 404 and still writes nothing`() {
        every { jdbc.queryForObject(match<String> { it.contains("FROM stocks WHERE id") }, Boolean::class.java, 999L) } returns false
        assertThatThrownBy { service.preview(userId, 999L, "BUY", 1, BigDecimal.ONE) }
            .isInstanceOf(NoSuchElementException::class.java)
        verify(exactly = 0) { audit.record(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `preview with risk checks disabled only guards quantity`() {
        every { limitService.effective(userId) } returns RiskLimit(userId = userId, isActive = false)
        every { rules.quantityGuard(5) } returns listOf(RuleResult("QuantityRule", true, "ok", 5.0, 0.0))
        val r = service.preview(userId, stockId, "BUY", 5, BigDecimal.ONE)
        assertThat(r.approved).isTrue()
        assertThat(r.checks.map { it.rule }).containsExactly("QuantityRule", "RiskChecksDisabled")
        verify(exactly = 0) { audit.record(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `preview runs in a read-only transaction`() {
        val m = RiskCheckerService::class.java.methods.first { it.name == "preview" }
        assertThat(m.getAnnotation(Transactional::class.java)?.readOnly).isTrue()
    }
}
