package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.domain.RuleVersionEntry
import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

/**
 * RuleSetService: 룰셋 CRUD의 사용자 스코핑, 버전 관리, 예외 전파 검증.
 *
 * RuleSetRepository(Mongo), QuantBacktestResultRepository(JPA), JdbcTemplate은
 * 이 서비스가 소유하지 않는 시스템 경계(DB)이므로 모킹한다. ObjectMapper는
 * 순수 라이브러리 변환기이므로 실제 인스턴스를 사용한다.
 *
 * runBacktest()는 QuantBacktestEngine + candle 로딩까지 포함하는 별도의 무거운 흐름이라
 * 이 파일에서는 다루지 않는다 — 백테스트 엔진 자체의 커버리지는 남은 과제로 플래그.
 */
class RuleSetServiceTest {

    private lateinit var ruleSetRepository: RuleSetRepository
    private lateinit var backtestResultRepository: QuantBacktestResultRepository
    private lateinit var jdbc: JdbcTemplate
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private lateinit var service: RuleSetService

    private val userId = 42L

    @BeforeEach
    fun setUp() {
        ruleSetRepository = mockk()
        backtestResultRepository = mockk()
        jdbc = mockk()
        service = RuleSetService(ruleSetRepository, backtestResultRepository, jdbc, objectMapper)
    }

    private fun document(id: String = "rs-1", owner: Long = userId) = RuleSetDocument(
        id = id,
        userId = owner,
        name = "모멘텀 전략",
        description = "설명",
        ruleDefinition = mapOf("entryRules" to mapOf("operator" to "AND", "conditions" to emptyList<Any>())),
    )

    // ─── create ─────────────────────────────────────────────────────────────

    @Test
    fun `create는 요청한 userId로 스코핑된 룰셋을 저장한다`() {
        val savedSlot = slot<RuleSetDocument>()
        // 실제 Mongo save()는 신규 문서에 id를 채워 반환한다 — 목도 동일하게 흉내낸다.
        every { ruleSetRepository.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = "generated-id") }

        val req = CreateRuleSetRequest(
            name = "새 전략", description = "desc",
            ruleDefinition = mapOf("k" to "v"),
        )
        val response = service.create(userId, req)

        assertEquals(userId, savedSlot.captured.userId)
        assertEquals("새 전략", savedSlot.captured.name)
        assertEquals(userId, response.userId)
        assertEquals("새 전략", response.name)
    }

    // ─── findById ───────────────────────────────────────────────────────────

    @Test
    fun `findById는 존재하지 않으면 NoSuchElementException을 던진다`() {
        every { ruleSetRepository.findByIdAndUserId("missing", userId) } returns Optional.empty()

        assertThrows(NoSuchElementException::class.java) {
            service.findById("missing", userId)
        }
    }

    @Test
    fun `findById는 존재하면 응답으로 변환해 반환한다`() {
        every { ruleSetRepository.findByIdAndUserId("rs-1", userId) } returns Optional.of(document())

        val response = service.findById("rs-1", userId)

        assertEquals("rs-1", response.id)
        assertEquals("모멘텀 전략", response.name)
    }

    // ─── update ─────────────────────────────────────────────────────────────

    @Test
    fun `update는 존재하지 않으면 NoSuchElementException을 던진다`() {
        every { ruleSetRepository.findByIdAndUserId("missing", userId) } returns Optional.empty()

        assertThrows(NoSuchElementException::class.java) {
            service.update("missing", userId, UpdateRuleSetRequest(name = "새 이름"))
        }
    }

    @Test
    fun `update는 이름과 설명을 변경하고 저장한다`() {
        val doc = document()
        every { ruleSetRepository.findByIdAndUserId("rs-1", userId) } returns Optional.of(doc)
        every { ruleSetRepository.save(any()) } answers { firstArg() }

        val response = service.update(
            "rs-1", userId,
            UpdateRuleSetRequest(name = "개명된 전략", description = "새 설명"),
        )

        assertEquals("개명된 전략", response.name)
        assertEquals("개명된 전략", doc.name)
        assertEquals("새 설명", doc.description)
        // ruleDefinition을 바꾸지 않았으므로 버전은 그대로다
        assertEquals(1, doc.version)
    }

    @Test
    fun `update는 ruleDefinition 변경 시 버전을 증가시키고 이전 버전을 스냅샷한다`() {
        val doc = document()
        every { ruleSetRepository.findByIdAndUserId("rs-1", userId) } returns Optional.of(doc)
        every { ruleSetRepository.save(any()) } answers { firstArg() }

        service.update(
            "rs-1", userId,
            UpdateRuleSetRequest(ruleDefinition = mapOf("entryRules" to mapOf("operator" to "OR", "conditions" to emptyList<Any>())), changeSummary = "진입 조건 변경"),
        )

        assertEquals(2, doc.version)
        assertEquals(1, doc.versions.size)
        assertEquals("진입 조건 변경", doc.versions[0].changeSummary)
    }

    // ─── delete ─────────────────────────────────────────────────────────────

    @Test
    fun `delete는 존재하지 않으면 NoSuchElementException을 던진다`() {
        every { ruleSetRepository.findByIdAndUserId("missing", userId) } returns Optional.empty()

        assertThrows(NoSuchElementException::class.java) {
            service.delete("missing", userId)
        }
        verify(exactly = 0) { ruleSetRepository.delete(any()) }
    }

    @Test
    fun `delete는 존재하면 리포지토리에서 삭제한다`() {
        val doc = document()
        every { ruleSetRepository.findByIdAndUserId("rs-1", userId) } returns Optional.of(doc)
        every { ruleSetRepository.delete(doc) } returns Unit

        service.delete("rs-1", userId)

        verify { ruleSetRepository.delete(doc) }
    }

    // ─── getVersionHistory ──────────────────────────────────────────────────

    @Test
    fun `getVersionHistory는 버전 내림차순으로 정렬해 반환한다`() {
        val doc = document()
        doc.versions.add(RuleVersionEntry(1, emptyMap(), "fp1", null, java.time.Instant.now()))
        doc.versions.add(RuleVersionEntry(3, emptyMap(), "fp3", null, java.time.Instant.now()))
        doc.versions.add(RuleVersionEntry(2, emptyMap(), "fp2", null, java.time.Instant.now()))
        every { ruleSetRepository.findByIdAndUserId("rs-1", userId) } returns Optional.of(doc)

        val history = service.getVersionHistory("rs-1", userId)

        assertEquals(listOf(3, 2, 1), history.map { it.version })
    }

    // ─── listBacktestResults ────────────────────────────────────────────────

    @Test
    fun `listBacktestResults는 룰셋이 존재하지 않으면 NoSuchElementException을 던진다`() {
        every { ruleSetRepository.findByIdAndUserId("missing", userId) } returns Optional.empty()

        assertThrows(NoSuchElementException::class.java) {
            service.listBacktestResults("missing", userId)
        }
        verify(exactly = 0) { backtestResultRepository.findAllByRuleSetId(any()) }
    }

    @Test
    fun `다른 사용자 소유 룰셋에는 findByIdAndUserId 스코핑으로 접근할 수 없다`() {
        // 리포지토리 쿼리 자체가 userId로 스코핑되므로, 존재하지만 소유자가 다른 문서는
        // findByIdAndUserId(id, 다른userId) 호출에서 Optional.empty()로 취급된다.
        every { ruleSetRepository.findByIdAndUserId("rs-1", 999L) } returns Optional.empty()

        assertThrows(NoSuchElementException::class.java) {
            service.findById("rs-1", 999L)
        }
    }
}
