package com.monticker.api.screener.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.screener.domain.ScreenerCriteria
import com.monticker.api.screener.domain.ScreenerEventFilter
import com.monticker.api.screener.infrastructure.SavedScreenRepository
import com.monticker.api.screener.infrastructure.SavedScreenRow
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class SavedScreenServiceTest {

    private val repo = mockk<SavedScreenRepository>(relaxed = true)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val service = SavedScreenService(repo, mapper)

    private fun row(id: Long, json: String) = SavedScreenRow(id, 1L, "s$id", json, Instant.EPOCH, Instant.EPOCH)

    @Test
    fun `create validates criteria, locks the user, then checks the cap before inserting`() {
        every { repo.count(1L) } returns 3
        every { repo.existsName(1L, "급등 반도체", null) } returns false
        val json = slot<String>()
        every { repo.insert(1L, "급등 반도체", capture(json)) } returns 10L
        every { repo.find(1L, 10L) } answers { row(10L, json.captured) }

        val saved = service.create(1L, "  급등 반도체 ", ScreenerCriteria(sectors = listOf("반도체"), minChange = 3.0, events = listOf(ScreenerEventFilter.NEWS)))

        assertThat(saved.criteria.minChange).isEqualTo(3.0)
        assertThat(saved.criteria.events).containsExactly(ScreenerEventFilter.NEWS)
        verifyOrder { repo.lockUser(1L); repo.count(1L); repo.insert(1L, any(), any()) }
    }

    @Test
    fun `create rejects invalid criteria before touching the database`() {
        assertThatThrownBy { service.create(1L, "x", ScreenerCriteria(sort = "random()")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { repo.insert(any(), any(), any()) }
    }

    @Test
    fun `create enforces the per-user cap and unique names`() {
        every { repo.count(1L) } returns SavedScreenService.MAX_PER_USER
        assertThatThrownBy { service.create(1L, "a", ScreenerCriteria()) }.isInstanceOf(BusinessRuleException::class.java)

        every { repo.count(1L) } returns 0
        every { repo.existsName(1L, "a", null) } returns true
        assertThatThrownBy { service.create(1L, "a", ScreenerCriteria()) }.isInstanceOf(BusinessRuleException::class.java)
    }

    @Test
    fun `names must be 1 to 40 characters`() {
        assertThatThrownBy { service.create(1L, "   ", ScreenerCriteria()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.create(1L, "x".repeat(41), ScreenerCriteria()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `update and delete of another user's screen look like not found`() {
        every { repo.find(1L, 99L) } returns null
        every { repo.delete(1L, 99L) } returns false

        assertThatThrownBy { service.update(1L, 99L, "a", ScreenerCriteria()) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.delete(1L, 99L) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `rows whose stored criteria no longer validate are left out of the list`() {
        every { repo.findAll(1L) } returns listOf(
            row(1L, """{"market":"all","sort":"amount"}"""),
            row(2L, """{"market":"mars","sort":"amount"}"""),
        )

        assertThat(service.list(1L).map { it.id }).containsExactly(1L)
    }
}
