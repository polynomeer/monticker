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
    private val service = RuleSetService(ruleSetRepository, backtestResultRepository, jdbc, ObjectMapper())

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
}
