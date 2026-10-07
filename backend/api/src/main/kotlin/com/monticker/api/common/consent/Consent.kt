package com.monticker.api.common.consent

import jakarta.persistence.*
import java.time.Instant

/**
 * ADR-068 — 동의 항목. 무엇에 동의했는지가 법적 증빙이므로 화면 문구가 아니라 이 식별자와 문서 버전으로 남긴다.
 */
enum class ConsentType {
    TERMS,
    PRIVACY,
    AGE_OVER_19,
    MARKETING,
    BROKERAGE_DELEGATION,
    BROKERAGE_NO_CUSTODY,
    BROKERAGE_LOSS_ATTRIBUTION,
}

/** 동의를 받는 지점별 필수·선택 항목. */
enum class ConsentGroup(val required: Set<ConsentType>, val optional: Set<ConsentType> = emptySet()) {
    SIGNUP(
        required = setOf(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_OVER_19),
        optional = setOf(ConsentType.MARKETING),
    ),
    BROKERAGE_CONNECT(
        required = setOf(ConsentType.BROKERAGE_DELEGATION, ConsentType.BROKERAGE_NO_CUSTODY, ConsentType.BROKERAGE_LOSS_ATTRIBUTION),
    ),
}

/**
 * 동의 대상 문서의 현재 버전. 약관·처리방침 문구를 바꾸면 여기 버전을 올린다 — 올리면 기존 동의는 현재 버전이 아니게 되어
 * 다음 로그인 때 다시 동의를 받는다(ConsentService.missingRequired).
 * 법률 검토 전 초안이라 'draft' 버전이다(docs/legal-review-brief.md).
 */
object ConsentDocuments {
    private val VERSIONS = mapOf(
        ConsentType.TERMS to "terms-draft-2026-10",
        ConsentType.PRIVACY to "privacy-draft-2026-10",
        ConsentType.AGE_OVER_19 to "v1",
        ConsentType.MARKETING to "v1",
        ConsentType.BROKERAGE_DELEGATION to "v1",
        ConsentType.BROKERAGE_NO_CUSTODY to "v1",
        ConsentType.BROKERAGE_LOSS_ATTRIBUTION to "v1",
    )

    fun versionOf(type: ConsentType): String = VERSIONS.getValue(type)
}

/** 어디서 받은 동의인가 — 같은 항목이라도 받은 지점이 증빙에 필요하다. */
enum class ConsentSource { SIGNUP, SOCIAL_SIGNUP, CONSENT_PROMPT, BROKERAGE_CONNECT, SETTINGS }

/**
 * 동의·철회 기록. 추가만 한다(append-only) — 현재 상태는 항목별 가장 최근 행이다. 철회도 행을 지우지 않고
 * agreed=false 행을 덧붙인다. 언제 무엇에 동의했는지가 남아야 증빙이 된다.
 */
@Entity
@Table(name = "user_consents")
class UserConsent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 40)
    val consentType: ConsentType,

    @Column(name = "document_version", nullable = false, length = 40)
    val documentVersion: String,

    @Column(nullable = false)
    val agreed: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val source: ConsentSource,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant = Instant.now(),
)
