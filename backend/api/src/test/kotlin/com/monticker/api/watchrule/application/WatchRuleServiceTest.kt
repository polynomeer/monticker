package com.monticker.api.watchrule.application

import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

/** ADR-077 — 규칙 생성 검증: 전략 신호 접근, 복합 조건, 하루 한도, 이름. */
class WatchRuleServiceTest {
    private val ruleRepo = mockk<WatchRuleRepository>()
    private val execRepo = mockk<WatchRuleExecutionRepository>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>()
    private val access = mockk<StrategySignalAccess>()
    private val guards = mockk<WatchRuleGuards>(relaxed = true)
    private val service = WatchRuleService(ruleRepo, execRepo, jdbc, access, guards)

    init {
        every { jdbc.queryForObject(any<String>(), Boolean::class.java, any()) } returns true
        every { ruleRepo.save(any()) } answers { firstArg() }
    }

    @Test
    fun `a strategy signal rule needs access to the strategy`() {
        every { access.canAccess(1L, "rs1") } returns false
        assertThatThrownBy {
            service.create(1L, 5L, WatchRule.QUANT_SIGNAL, "BUY", 1, 0, 0, ruleSetId = "rs1", signalDirection = "BUY")
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("구독")
        verify(exactly = 0) { ruleRepo.save(any()) }
    }

    @Test
    fun `a strategy signal rule stores the strategy and direction`() {
        every { access.canAccess(1L, "rs1") } returns true
        val r = service.create(1L, 5L, WatchRule.QUANT_SIGNAL, "BUY", 1, 0, 0, name = "  골든크로스  ", ruleSetId = "rs1", signalDirection = "buy", dailyLimit = 3)
        assertThat(r.ruleSetId).isEqualTo("rs1")
        assertThat(r.signalDirection).isEqualTo("BUY")
        assertThat(r.name).isEqualTo("골든크로스")
        assertThat(r.dailyLimit).isEqualTo(3)
    }

    @Test
    fun `a compound condition cannot repeat the primary event or use unknown types`() {
        assertThatThrownBy {
            service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, requiredEventTypes = listOf("VOLUME_SURGE"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, requiredEventTypes = listOf("NEWS"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a compound condition gets the default window`() {
        val r = service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, requiredEventTypes = listOf("PRICE_SPIKE"))
        assertThat(r.requiredTypes()).containsExactly("PRICE_SPIKE")
        assertThat(r.conditionWindowSec).isEqualTo(WatchRuleExecutor.DEFAULT_WINDOW_SEC)
    }

    @Test
    fun `the daily limit must be between 1 and 1000`() {
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, dailyLimit = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── 소유권: 남의 룰은 없는 룰과 구분되지 않는다(id 열거 방지) ─────────────────────

    private fun othersRule() = WatchRule(id = 1L, userId = 2L, stockId = 5L, eventType = "VOLUME_SURGE", side = WatchRuleSide.BUY, quantity = 1)

    private fun errorsFor(call: () -> Unit): Pair<Throwable?, Throwable?> {
        every { ruleRepo.findById(1L) } returns Optional.of(othersRule())
        val others = runCatching(call).exceptionOrNull()
        every { ruleRepo.findById(1L) } returns Optional.empty()
        val missing = runCatching(call).exceptionOrNull()
        return others to missing
    }

    @Test
    fun `updating another user's rule looks exactly like updating a missing one`() {
        val (others, missing) = errorsFor { service.update(1L, 1L, 3, null, null, false) }
        assertThat(others).isInstanceOf(NoSuchElementException::class.java)
        assertThat(others!!.message).isEqualTo(missing!!.message)
        verify(exactly = 0) { ruleRepo.save(any()) }
    }

    @Test
    fun `deleting another user's rule looks exactly like deleting a missing one`() {
        val (others, missing) = errorsFor { service.delete(1L, 1L) }
        assertThat(others).isInstanceOf(NoSuchElementException::class.java)
        assertThat(others!!.message).isEqualTo(missing!!.message)
        verify(exactly = 0) { ruleRepo.delete(any()) }
    }
}
