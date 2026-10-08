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
    private val targets = mockk<WatchRuleTargets>()
    private val service = WatchRuleService(ruleRepo, execRepo, jdbc, access, guards, targets)

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

    // ── ADR-095 ──────────────────────────────────────────────────────────

    // 남의 그룹은 없는 그룹과 같은 404(security-review H6) — 메시지도 같다.
    @Test
    fun `a group rule on someone else's group is indistinguishable from a missing group`() {
        every { targets.ownsGroup(1L, 77L) } returns false
        every { targets.ownsGroup(1L, 78L) } returns false
        val others = runCatching { service.create(1L, null, "VOLUME_SURGE", "BUY", 1, 0, 0, targetType = "GROUP", targetGroupId = 77L) }.exceptionOrNull()
        val missing = runCatching { service.create(1L, null, "VOLUME_SURGE", "BUY", 1, 0, 0, targetType = "GROUP", targetGroupId = 78L) }.exceptionOrNull()
        assertThat(others).isInstanceOf(NoSuchElementException::class.java)
        assertThat(missing).isInstanceOf(NoSuchElementException::class.java)
        assertThat(others!!.message!!.replace("77", "")).isEqualTo(missing!!.message!!.replace("78", ""))
        verify(exactly = 0) { ruleRepo.save(any()) }
    }

    @Test
    fun `a group rule on my group stores the group and no stock`() {
        every { targets.ownsGroup(1L, 77L) } returns true
        val r = service.create(1L, null, "VOLUME_SURGE", "BUY", null, 0, 0, targetType = "group", targetGroupId = 77L,
            sizeType = "EQUITY_PCT", equityPct = java.math.BigDecimal("5"))
        assertThat(r.targetGroupId).isEqualTo(77L)
        assertThat(r.stockId).isNull()
        assertThat(r.quantity).isNull()
        assertThat(r.equityPct).isEqualByComparingTo("5")
    }

    @Test
    fun `targets must be exclusive - a stock rule without a stock or a group rule with a stock is rejected`() {
        assertThatThrownBy { service.create(1L, null, "VOLUME_SURGE", "BUY", 1, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, targetType = "GROUP", targetGroupId = 77L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `limit offsets are bounded to plus or minus 1000 bps and only for limit rules`() {
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, orderType = "LIMIT", limitOffsetBps = 1001) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("오프셋")
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, orderType = "LIMIT", limitOffsetBps = -1001) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, orderType = "LIMIT") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, limitOffsetBps = 10) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val ok = service.create(1L, 5L, "VOLUME_SURGE", "BUY", 1, 0, 0, orderType = "LIMIT", limitOffsetBps = -1000)
        assertThat(ok.limitOffsetBps).isEqualTo(-1000)
    }

    @Test
    fun `equity percent is bounded to 1 to 25 and excludes a share quantity`() {
        listOf("0.99", "25.01", "0").forEach { pct ->
            assertThatThrownBy {
                service.create(1L, 5L, "VOLUME_SURGE", "BUY", null, 0, 0, sizeType = "EQUITY_PCT", equityPct = java.math.BigDecimal(pct))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy {
            service.create(1L, 5L, "VOLUME_SURGE", "BUY", 3, 0, 0, sizeType = "EQUITY_PCT", equityPct = java.math.BigDecimal("5"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.create(1L, 5L, "VOLUME_SURGE", "BUY", null, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a group rule whose group was deleted cannot be switched back on`() {
        val rule = WatchRule(id = 9L, userId = 1L, stockId = null, eventType = "VOLUME_SURGE", side = WatchRuleSide.BUY, quantity = 1,
            isActive = false, targetType = com.monticker.api.watchrule.domain.WatchRuleTargetType.GROUP, targetGroupId = 77L)
        every { ruleRepo.findById(9L) } returns Optional.of(rule)
        every { targets.ownsGroup(1L, 77L) } returns false
        assertThatThrownBy { service.update(1L, 9L, null, null, null, true) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("삭제")
    }
}
