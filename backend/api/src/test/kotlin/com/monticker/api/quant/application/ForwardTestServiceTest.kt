package com.monticker.api.quant.application

import com.monticker.api.quant.domain.*
import com.monticker.api.quant.infrastructure.QuantForwardTestEquityRepository
import com.monticker.api.quant.infrastructure.QuantForwardTestRepository
import com.monticker.api.quant.infrastructure.QuantSignalRepository
import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional

class ForwardTestServiceTest {

    private val ruleSetRepository = mockk<RuleSetRepository>()
    private val ruleSetService = mockk<RuleSetService>()
    private val forwardTestRepository = mockk<QuantForwardTestRepository>(relaxed = true)
    private val signalRepository = mockk<QuantSignalRepository>(relaxed = true)
    private val equityRepository = mockk<QuantForwardTestEquityRepository>(relaxed = true)
    private val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)

    private val service = ForwardTestService(
        ruleSetRepository, ruleSetService, forwardTestRepository,
        signalRepository, equityRepository, messagingTemplate,
    )

    private fun candle(date: LocalDate, close: Double) = DailyCandle(
        date = date, open = BigDecimal(close), high = BigDecimal(close),
        low = BigDecimal(close), close = BigDecimal(close), volume = 1000L,
    )

    private fun alwaysTrueEntry() = RuleDefinition(
        entryRules = RuleGroup("OR", listOf(RuleCondition("CLOSE_VS_MA", "GTE", mapOf("period" to 1)))),
        exitRules  = RuleGroup("OR", listOf(RuleCondition("PROFIT_RATE", "GTE", value = 999_999.0))),
        positionSizing = PositionSizing("FIXED_RATIO", 50.0),
    )

    private fun alwaysTrueExit() = RuleDefinition(
        entryRules = RuleGroup("AND", listOf(RuleCondition("CLOSE_VS_MA", "GT", mapOf("period" to 1), value = 999_999.0))),
        exitRules  = RuleGroup("OR", listOf(RuleCondition("PROFIT_RATE", "GTE", value = -999.0))),
        positionSizing = PositionSizing("FIXED_RATIO", 50.0),
    )

    private fun doc(ruleDef: Map<String, Any> = emptyMap()) = RuleSetDocument(
        id = "rs1", userId = 1L, name = "test", status = RuleSetStatus.BACKTESTED.name, ruleDefinition = ruleDef,
    )

    // ── start / stop ─────────────────────────────────────────────────────────────

    @Test
    fun `start creates a running forward test and publishes the ruleset`() {
        val d = doc()
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(d)
        every { ruleSetRepository.save(any()) } returnsArgument 0
        every { forwardTestRepository.save(any()) } answers { firstArg() }

        val result = service.start("rs1", 1L, StartForwardTestRequest(stockId = 5, initialCapital = 1_000_000.0))

        assertThat(result.status).isEqualTo("RUNNING")
        assertThat(result.cash).isEqualTo(1_000_000.0)
        assertThat(d.status).isEqualTo(RuleSetStatus.RUNNING.name)
    }

    @Test
    fun `start rejects a ruleset that has not been backtested yet`() {
        val d = doc().also { it.status = RuleSetStatus.DRAFT.name }
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(d)

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            service.start("rs1", 1L, StartForwardTestRequest(stockId = 5))
        }
    }

    @Test
    fun `stop marks the forward test stopped and reverts the ruleset to backtested`() {
        val d = doc().also { it.status = RuleSetStatus.RUNNING.name }
        val ft = QuantForwardTest(id = 1, ruleSetId = "rs1", ruleSetVersion = 1, stockId = 5,
            initialCapital = BigDecimal(1_000_000), cash = BigDecimal(1_000_000))
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(d)
        every { ruleSetRepository.save(any()) } returnsArgument 0
        every { forwardTestRepository.findByRuleSetIdAndStatus("rs1", ForwardTestStatus.RUNNING) } returns Optional.of(ft)
        every { forwardTestRepository.save(any()) } returnsArgument 0
        every { equityRepository.save(any()) } returnsArgument 0
        every { equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(1) } returns emptyList()
        every { signalRepository.findAllByForwardTestIdOrderBySignalTimeDesc(1) } returns emptyList()

        val result = service.stop("rs1", 1L)

        assertThat(result.status).isEqualTo("STOPPED")
        assertThat(d.status).isEqualTo(RuleSetStatus.BACKTESTED.name)
    }

    // ── evaluateOne ──────────────────────────────────────────────────────────────

    @Test
    fun `evaluateOne skips when already evaluated for that date`() {
        val ft = QuantForwardTest(id = 1, ruleSetId = "rs1", ruleSetVersion = 1, stockId = 5,
            initialCapital = BigDecimal(1_000_000), cash = BigDecimal(1_000_000),
            lastEvaluatedDate = LocalDate.of(2026, 1, 2))

        service.evaluateOne(ft, LocalDate.of(2026, 1, 2))

        verify(exactly = 0) { ruleSetRepository.findById(any()) }
    }

    @Test
    fun `evaluateOne skips when todays candle has not landed yet`() {
        val ft = QuantForwardTest(id = 1, ruleSetId = "rs1", ruleSetVersion = 1, stockId = 5,
            initialCapital = BigDecimal(1_000_000), cash = BigDecimal(1_000_000))
        val today = LocalDate.of(2026, 1, 5)
        every { ruleSetRepository.findById("rs1") } returns Optional.of(doc())
        every { ruleSetService.parseRuleDefinition(any()) } returns alwaysTrueEntry()
        every { ruleSetService.loadDailyCandles(5, any(), today) } returns listOf(candle(today.minusDays(1), 100.0))

        service.evaluateOne(ft, today)

        verify(exactly = 0) { forwardTestRepository.save(any()) }
        verify(exactly = 0) { signalRepository.save(any()) }
    }

    @Test
    fun `evaluateOne opens a position and emits a BUY signal when entry fires while flat`() {
        val today = LocalDate.of(2026, 1, 5)
        val ft = QuantForwardTest(id = 1, ruleSetId = "rs1", ruleSetVersion = 1, stockId = 5,
            initialCapital = BigDecimal(1_000_000), cash = BigDecimal(1_000_000))
        every { ruleSetRepository.findById("rs1") } returns Optional.of(doc())
        every { ruleSetService.parseRuleDefinition(any()) } returns alwaysTrueEntry()
        every { ruleSetService.loadDailyCandles(5, any(), today) } returns listOf(candle(today, 100.0))
        every { equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(1) } returns emptyList()
        every { forwardTestRepository.save(any()) } returnsArgument 0
        every { equityRepository.save(any()) } returnsArgument 0
        val signalSlot = slot<QuantSignal>()
        every { signalRepository.save(capture(signalSlot)) } answers { firstArg() }

        service.evaluateOne(ft, today)

        assertThat(ft.isHolding).isTrue()
        assertThat(ft.lastEvaluatedDate).isEqualTo(today)
        assertThat(signalSlot.captured.direction).isEqualTo(SignalDirection.BUY)
        verify { messagingTemplate.convertAndSend("/topic/rulesets/rs1/signals", any<Map<String, Any>>()) }
        verify { equityRepository.save(any()) }
    }

    @Test
    fun `evaluateOne closes the position and emits a SELL signal when exit fires while holding`() {
        val today = LocalDate.of(2026, 1, 5)
        val ft = QuantForwardTest(id = 1, ruleSetId = "rs1", ruleSetVersion = 1, stockId = 5,
            initialCapital = BigDecimal(1_000_000), cash = BigDecimal(500_000))
        ft.openPosition(qty = 10, price = BigDecimal(100), date = today.minusDays(3))
        every { ruleSetRepository.findById("rs1") } returns Optional.of(doc())
        every { ruleSetService.parseRuleDefinition(any()) } returns alwaysTrueExit()
        every { ruleSetService.loadDailyCandles(5, any(), today) } returns listOf(candle(today, 120.0))
        every { equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(1) } returns emptyList()
        every { forwardTestRepository.save(any()) } returnsArgument 0
        every { equityRepository.save(any()) } returnsArgument 0
        val signalSlot = slot<QuantSignal>()
        every { signalRepository.save(capture(signalSlot)) } answers { firstArg() }

        service.evaluateOne(ft, today)

        assertThat(ft.isHolding).isFalse()
        assertThat(signalSlot.captured.direction).isEqualTo(SignalDirection.SELL)
    }

    @Test
    fun `evaluateOne records an equity point with no signal when neither entry nor exit fires`() {
        val today = LocalDate.of(2026, 1, 5)
        val ft = QuantForwardTest(id = 1, ruleSetId = "rs1", ruleSetVersion = 1, stockId = 5,
            initialCapital = BigDecimal(1_000_000), cash = BigDecimal(1_000_000))
        val neverEntry = RuleDefinition(
            entryRules = RuleGroup("AND", listOf(RuleCondition("CLOSE_VS_MA", "GT", mapOf("period" to 1), value = 999_999.0))),
            exitRules  = RuleGroup("OR", listOf(RuleCondition("PROFIT_RATE", "GTE", value = 8.0))),
            positionSizing = PositionSizing("FIXED_RATIO", 10.0),
        )
        every { ruleSetRepository.findById("rs1") } returns Optional.of(doc())
        every { ruleSetService.parseRuleDefinition(any()) } returns neverEntry
        every { ruleSetService.loadDailyCandles(5, any(), today) } returns listOf(candle(today, 100.0))
        every { equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(1) } returns emptyList()
        every { forwardTestRepository.save(any()) } returnsArgument 0
        every { equityRepository.save(any()) } returnsArgument 0

        service.evaluateOne(ft, today)

        assertThat(ft.isHolding).isFalse()
        verify(exactly = 0) { signalRepository.save(any()) }
        verify(exactly = 0) { messagingTemplate.convertAndSend(any<String>(), any<Any>()) }
        verify { equityRepository.save(any()) }
    }
}
