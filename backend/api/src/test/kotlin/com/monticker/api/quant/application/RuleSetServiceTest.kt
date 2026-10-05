package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.domain.RuleSetStatus
import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

class RuleSetServiceTest {

    private val ruleSetRepository = mockk<RuleSetRepository>()
    private val backtestResultRepository = mockk<QuantBacktestResultRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val performanceQuery = mockk<StrategyPerformanceQuery>()
    private val service = RuleSetService(ruleSetRepository, backtestResultRepository, jdbc, ObjectMapper(), performanceQuery)

    private fun doc(status: String) = RuleSetDocument(id = "rs1", userId = 1L, name = "original", status = status)

    // ADR-024: 포워드 테스트 운용 중(RUNNING)에는 룰셋을 수정할 수 없다.

    @Test
    fun `update rejects any change while the ruleset is running a forward test`() {
        val d = doc(RuleSetStatus.RUNNING.name)
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(d)

        assertThrows<IllegalArgumentException> {
            service.update("rs1", 1L, UpdateRuleSetRequest(name = "renamed while running"))
        }
        assertThat(d.name).isEqualTo("original")
    }

    @Test
    fun `update succeeds when the ruleset is not running`() {
        val d = doc(RuleSetStatus.BACKTESTED.name)
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(d)
        every { ruleSetRepository.save(any()) } returnsArgument 0

        service.update("rs1", 1L, UpdateRuleSetRequest(name = "renamed after stop"))

        assertThat(d.name).isEqualTo("renamed after stop")
    }

    // ── universe (종목 선택기용 유니버스 필터) ───────────────────────────────────────

    @Test
    fun `create stores the universe filter when provided`() {
        every { ruleSetRepository.save(any()) } answers { firstArg<RuleSetDocument>().copy(id = "generated") }

        val response = service.create(
            1L,
            CreateRuleSetRequest(
                name = "test", ruleDefinition = emptyMap<String, Any>(),
                universeJson = mapOf("market" to "domestic", "marketCapTier" to "large"),
            ),
        )

        assertThat(response.universeJson).contains("\"market\":\"domestic\"", "\"marketCapTier\":\"large\"")
    }

    @Test
    fun `update replaces the universe filter without touching rule definition version`() {
        val d = doc(RuleSetStatus.BACKTESTED.name)
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(d)
        every { ruleSetRepository.save(any()) } returnsArgument 0

        service.update("rs1", 1L, UpdateRuleSetRequest(universeJson = mapOf("market" to "overseas")))

        assertThat(d.universeJson).isEqualTo(mapOf("market" to "overseas"))
        assertThat(d.version).isEqualTo(1)
    }

    // ── delete — ADR-035: 구독자가 있는 마켓 공유 룰셋은 삭제할 수 없다 ────────────────

    @Test
    fun `delete rejects a ruleset that still has subscribers`() {
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(doc(RuleSetStatus.BACKTESTED.name))
        every {
            jdbc.queryForObject("SELECT COALESCE(SUM(subscribe_count), 0) FROM strategy_market WHERE ruleset_id = ?", Long::class.java, "rs1")
        } returns 3L

        assertThrows<IllegalArgumentException> { service.delete("rs1", 1L) }
    }

    @Test
    fun `delete succeeds when the ruleset has no subscribers`() {
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(doc(RuleSetStatus.BACKTESTED.name))
        every {
            jdbc.queryForObject("SELECT COALESCE(SUM(subscribe_count), 0) FROM strategy_market WHERE ruleset_id = ?", Long::class.java, "rs1")
        } returns 0L
        every { ruleSetRepository.delete(any()) } returns Unit

        service.delete("rs1", 1L)

        io.mockk.verify { ruleSetRepository.delete(any()) }
    }

    // ── ADR-078 목록 성과 요약 ────────────────────────────────────────────────────

    @Test
    fun `findByUser attaches the batched performance summary to each ruleset`() {
        every { ruleSetRepository.findAllByUserId(1L) } returns listOf(doc(RuleSetStatus.BACKTESTED.name), doc(RuleSetStatus.DRAFT.name).copy(id = "rs2"))
        val perf = StrategyPerformance(backtest = null, forward = ForwardSummary("RUNNING", "2026-01-01T00:00:00Z", null, 0.9, 9, 10))
        every { performanceQuery.summarize(listOf("rs1", "rs2")) } returns mapOf("rs1" to perf)

        val list = service.findByUser(1L)

        assertThat(list.first { it.id == "rs1" }.performance).isEqualTo(perf)
        assertThat(list.first { it.id == "rs2" }.performance).isNull()
        io.mockk.verify(exactly = 1) { performanceQuery.summarize(any()) }
    }

    // ── ADR-079 보조 데이터 지표 입력 검증 ─────────────────────────────────────────

    private fun defWith(cond: Map<String, Any>) = mapOf(
        "entryRules" to mapOf("operator" to "AND", "conditions" to listOf(cond)),
        "exitRules" to mapOf("operator" to "OR", "conditions" to emptyList<Any>()),
        "positionSizing" to mapOf("type" to "FIXED_RATIO", "value" to 10),
    )

    @Test
    fun `create rejects an unknown disclosure category`() {
        assertThrows<IllegalArgumentException> {
            service.create(1L, CreateRuleSetRequest(name = "x", ruleDefinition = defWith(
                mapOf("indicator" to "DISCLOSURE", "comparator" to "WHATEVER", "params" to mapOf("period" to 5)),
            )))
        }
    }

    @Test
    fun `create rejects a news sentiment threshold outside -1 to 1`() {
        assertThrows<IllegalArgumentException> {
            service.create(1L, CreateRuleSetRequest(name = "x", ruleDefinition = defWith(
                mapOf("indicator" to "NEWS_SENTIMENT", "comparator" to "GT", "params" to mapOf("period" to 5), "value" to 30),
            )))
        }
    }

    @Test
    fun `create accepts valid aux indicator conditions`() {
        every { ruleSetRepository.save(any()) } answers { firstArg<RuleSetDocument>().copy(id = "generated") }

        service.create(1L, CreateRuleSetRequest(name = "x", ruleDefinition = defWith(
            mapOf("indicator" to "NEWS_SENTIMENT", "comparator" to "GT", "params" to mapOf("period" to 5), "value" to 0.3),
        )))
    }

    @Test
    fun `create rejects out-of-range hard exits`() {
        val base = defWith(mapOf("indicator" to "RSI", "comparator" to "LT", "value" to 30))
        assertThrows<IllegalArgumentException> {
            service.create(1L, CreateRuleSetRequest(name = "x", ruleDefinition = base + ("hardExits" to mapOf("maxHoldDays" to 0))))
        }
        assertThrows<IllegalArgumentException> {
            service.create(1L, CreateRuleSetRequest(name = "x", ruleDefinition = base + ("hardExits" to mapOf("trailingStopPct" to 80))))
        }
    }

    @Test
    fun `parseRuleDefinition reads hard exits and defaults to none`() {
        val base = defWith(mapOf("indicator" to "RSI", "comparator" to "LT", "value" to 30))

        assertThat(service.parseRuleDefinition(base).hardExits.maxHoldDays).isNull()
        val parsed = service.parseRuleDefinition(base + ("hardExits" to mapOf("maxHoldDays" to 20, "trailingStopPct" to 7.5)))
        assertThat(parsed.hardExits.maxHoldDays).isEqualTo(20)
        assertThat(parsed.hardExits.trailingStopPct).isEqualTo(7.5)
    }
}
