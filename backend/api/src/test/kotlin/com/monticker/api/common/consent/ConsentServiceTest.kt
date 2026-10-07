package com.monticker.api.common.consent

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ConsentServiceTest {

    private val repo = mockk<UserConsentRepository>(relaxed = true)
    private val service = ConsentService(repo)

    private fun row(type: ConsentType, agreed: Boolean, version: String = ConsentDocuments.versionOf(type), at: Instant = Instant.now()) =
        UserConsent(userId = 1L, consentType = type, documentVersion = version, agreed = agreed, source = ConsentSource.SIGNUP, recordedAt = at)

    @Test
    fun `signup records the required items and the optional marketing consent at the current document versions`() {
        val saved = slot<Iterable<UserConsent>>()
        every { repo.saveAll(capture(saved)) } answers { saved.captured.toList() }

        val accepted = service.requireAndRecord(1L, ConsentGroup.SIGNUP, listOf("TERMS", "privacy", "AGE_OVER_19", "MARKETING"), ConsentSource.SIGNUP)

        assertThat(accepted).containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_OVER_19, ConsentType.MARKETING)
        assertThat(saved.captured.map { it.documentVersion to it.agreed })
            .allMatch { it.second }
        assertThat(saved.captured.first { it.consentType == ConsentType.TERMS }.documentVersion).isEqualTo(ConsentDocuments.versionOf(ConsentType.TERMS))
    }

    @Test
    fun `a missing required item is rejected and nothing is recorded`() {
        val e = assertThrows<IllegalArgumentException> {
            service.requireAndRecord(1L, ConsentGroup.SIGNUP, listOf("TERMS", "PRIVACY"), ConsentSource.SIGNUP)
        }

        assertThat(e.message).contains("만 19세 이상")
        verify(exactly = 0) { repo.saveAll(any<Iterable<UserConsent>>()) }
    }

    @Test
    fun `unknown items are rejected rather than silently dropped`() {
        assertThrows<IllegalArgumentException> {
            service.requireAndRecord(1L, ConsentGroup.SIGNUP, listOf("TERMS", "PRIVACY", "AGE_OVER_19", "SOMETHING"), ConsentSource.SIGNUP)
        }
    }

    @Test
    fun `items that do not belong to the group are not recorded there`() {
        val saved = slot<Iterable<UserConsent>>()
        every { repo.saveAll(capture(saved)) } answers { saved.captured.toList() }

        service.requireAndRecord(1L, ConsentGroup.BROKERAGE_CONNECT,
            listOf("BROKERAGE_DELEGATION", "BROKERAGE_NO_CUSTODY", "BROKERAGE_LOSS_ATTRIBUTION", "MARKETING"), ConsentSource.BROKERAGE_CONNECT)

        assertThat(saved.captured.map { it.consentType }).doesNotContain(ConsentType.MARKETING)
    }

    @Test
    fun `a social user with no records is missing every required signup item`() {
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns emptyList()

        assertThat(service.missingRequired(1L, ConsentGroup.SIGNUP))
            .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_OVER_19)
    }

    @Test
    fun `consent to an older document version must be given again`() {
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(
            row(ConsentType.TERMS, true, version = "terms-old"),
            row(ConsentType.PRIVACY, true),
            row(ConsentType.AGE_OVER_19, true),
        )

        assertThat(service.missingRequired(1L, ConsentGroup.SIGNUP)).containsExactly(ConsentType.TERMS)
    }

    @Test
    fun `the latest row wins, so a withdrawal after an agreement counts as not agreed`() {
        val now = Instant.now()
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(
            row(ConsentType.MARKETING, false, at = now),
            row(ConsentType.MARKETING, true, at = now.minusSeconds(60)),
        )

        assertThat(service.status(1L).first { it.type == ConsentType.MARKETING }.agreed).isFalse()
    }

    @Test
    fun `required items cannot be withdrawn`() {
        assertThrows<IllegalArgumentException> { service.withdraw(1L, ConsentType.TERMS, ConsentSource.SETTINGS) }
    }

    @Test
    fun `marketing alone can be agreed again from settings and becomes the latest state`() {
        val saved = slot<Iterable<UserConsent>>()
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(row(ConsentType.MARKETING, agreed = false))
        every { repo.saveAll(capture(saved)) } answers { saved.captured.toList() }

        service.agreeOptional(1L, ConsentType.MARKETING, ConsentSource.SETTINGS)

        val r = saved.captured.single()
        assertThat(r.consentType).isEqualTo(ConsentType.MARKETING)
        assertThat(r.agreed).isTrue()
        assertThat(r.source).isEqualTo(ConsentSource.SETTINGS)
        assertThat(r.documentVersion).isEqualTo(ConsentDocuments.versionOf(ConsentType.MARKETING))
    }

    @Test
    fun `agreeing again while already agreed at the current version adds no row`() {
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(row(ConsentType.MARKETING, agreed = true))

        service.agreeOptional(1L, ConsentType.MARKETING, ConsentSource.SETTINGS)

        verify(exactly = 0) { repo.saveAll(any<Iterable<UserConsent>>()) }
    }

    @Test
    fun `required items cannot be agreed one by one from settings`() {
        listOf(ConsentType.TERMS, ConsentType.BROKERAGE_DELEGATION).forEach { t ->
            assertThrows<IllegalArgumentException> { service.agreeOptional(1L, t, ConsentSource.SETTINGS) }
        }
        verify(exactly = 0) { repo.saveAll(any<Iterable<UserConsent>>()) }
    }

    @Test
    fun `isAgreed follows the latest row and the current document version`() {
        val now = Instant.now()
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(
            row(ConsentType.MARKETING, agreed = false, at = now), row(ConsentType.MARKETING, agreed = true, at = now.minusSeconds(60)),
        )
        assertThat(service.isAgreed(1L, ConsentType.MARKETING)).isFalse()

        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(row(ConsentType.MARKETING, agreed = true, version = "v0"))
        assertThat(service.isAgreed(1L, ConsentType.MARKETING)).isFalse()
    }

    @Test
    fun `re-consent after a terms revision needs only the revised item`() {
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns listOf(
            row(ConsentType.TERMS, true, version = "terms-old"),
            row(ConsentType.PRIVACY, true),
            row(ConsentType.AGE_OVER_19, true),
        )
        every { repo.saveAll(any<Iterable<UserConsent>>()) } answers { firstArg<Iterable<UserConsent>>().toList() }

        val accepted = service.requireMissingAndRecord(1L, ConsentGroup.SIGNUP, listOf("TERMS"), ConsentSource.CONSENT_PROMPT)

        assertThat(accepted).containsExactly(ConsentType.TERMS)
    }

    @Test
    fun `re-consent still refuses when a missing item is left out`() {
        every { repo.findAllByUserIdOrderByRecordedAtDescIdDesc(1L) } returns emptyList()

        assertThrows<IllegalArgumentException> {
            service.requireMissingAndRecord(1L, ConsentGroup.SIGNUP, listOf("TERMS"), ConsentSource.CONSENT_PROMPT)
        }
    }
}
