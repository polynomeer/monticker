package com.monticker.api.common.consent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 항목 하나의 현재 상태 — 화면이 "무엇에 동의했고 무엇이 빠졌나"를 그리는 데 쓴다. */
data class ConsentState(
    val type: ConsentType,
    val agreed: Boolean,
    val documentVersion: String?,
    val currentVersion: String,
    val recordedAt: Instant?,
)

/**
 * ADR-068 — 동의 기록. 호출자의 트랜잭션 안에서 기록한다: 가입·연동이 커밋되면 동의도 커밋되고, 롤백되면 같이 사라진다.
 */
@Service
@Transactional
class ConsentService(private val repo: UserConsentRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [group]의 필수 항목이 [given]에 모두 있는지 확인하고, 그 지점에서 받을 수 있는 항목(필수+선택)만 현재 버전으로 기록한다.
     * 필수가 빠지면 [IllegalArgumentException](400) — 화면이 막더라도 서버가 다시 확인한다.
     */
    fun requireAndRecord(userId: Long, group: ConsentGroup, given: Collection<String>, source: ConsentSource): Set<ConsentType> {
        val types = parse(given)
        val missing = group.required - types
        require(missing.isEmpty()) { "필수 동의 항목이 빠졌습니다: ${missing.joinToString { label(it) }}" }
        val accepted = types.filter { it in group.required || it in group.optional }.toSet()
        record(userId, accepted, source)
        return accepted
    }

    /**
     * 동의 화면(재동의) — 지금 빠진 필수 항목만 있으면 된다. 약관만 개정되면 빠진 건 TERMS 하나인데, 가입처럼 필수 전부를 요구하면
     * 400이 나고 화면 게이트가 계속 동의 화면으로 되돌려 보내 사용자가 앱에 들어가지 못했다(보안 리뷰).
     */
    fun requireMissingAndRecord(userId: Long, group: ConsentGroup, given: Collection<String>, source: ConsentSource): Set<ConsentType> {
        val types = parse(given)
        val stillMissing = missingRequired(userId, group) - types
        require(stillMissing.isEmpty()) { "필수 동의 항목이 빠졌습니다: ${stillMissing.joinToString { label(it) }}" }
        val accepted = types.filter { it in group.required || it in group.optional }.toSet()
        record(userId, accepted, source)
        return accepted
    }

    /** 현재 버전 기준으로 아직 동의하지 않은(또는 철회했거나 옛 버전에만 동의한) 필수 항목. */
    @Transactional(readOnly = true)
    fun missingRequired(userId: Long, group: ConsentGroup): Set<ConsentType> {
        val latest = latestByType(userId)
        return group.required.filterNot { t ->
            latest[t]?.let { it.agreed && it.documentVersion == ConsentDocuments.versionOf(t) } ?: false
        }.toSet()
    }

    @Transactional(readOnly = true)
    fun status(userId: Long): List<ConsentState> {
        val latest = latestByType(userId)
        return ConsentType.entries.map { t ->
            val row = latest[t]
            ConsentState(t, row?.agreed == true, row?.documentVersion, ConsentDocuments.versionOf(t), row?.recordedAt)
        }
    }

    /**
     * 선택 항목 하나에 (다시) 동의한다 — 설정 화면의 마케팅 수신 토글. 필수 항목은 이 길로 받지 않는다: 필수 동의는 가입·동의 화면에서
     * 묶음으로, 문서 전문을 보여 준 뒤 받는다([requireAndRecord]). 이미 현재 버전으로 동의한 상태면 행을 더 쌓지 않는다.
     */
    fun agreeOptional(userId: Long, type: ConsentType, source: ConsentSource) {
        require(isOptional(type)) { "${label(type)}은(는) 여기서 동의할 수 없는 항목입니다." }
        val latest = latestByType(userId)[type]
        if (latest != null && latest.agreed && latest.documentVersion == ConsentDocuments.versionOf(type)) return
        record(userId, setOf(type), source)
    }

    /** 그 항목의 최신 기록이 현재 버전의 동의인가. 마케팅(광고성 정보) 발송 경로는 보내기 직전에 이것을 확인해야 한다. */
    @Transactional(readOnly = true)
    fun isAgreed(userId: Long, type: ConsentType): Boolean =
        latestByType(userId)[type]?.let { it.agreed && it.documentVersion == ConsentDocuments.versionOf(type) } ?: false

    /** 선택 항목만 철회할 수 있다. 필수 항목 철회는 서비스 탈퇴다. */
    fun withdraw(userId: Long, type: ConsentType, source: ConsentSource) {
        require(ConsentGroup.entries.none { type in it.required }) { "필수 동의는 철회할 수 없습니다. 탈퇴로 처리해야 합니다." }
        repo.save(UserConsent(userId = userId, consentType = type, documentVersion = ConsentDocuments.versionOf(type), agreed = false, source = source))
        log.info("동의 철회: userId={} type={}", userId, type)
    }

    private fun record(userId: Long, types: Set<ConsentType>, source: ConsentSource) {
        val now = Instant.now()
        repo.saveAll(types.map { UserConsent(userId = userId, consentType = it, documentVersion = ConsentDocuments.versionOf(it), agreed = true, source = source, recordedAt = now) })
        log.info("동의 기록: userId={} source={} types={}", userId, source, types)
    }

    private fun isOptional(type: ConsentType) =
        ConsentGroup.entries.any { type in it.optional } && ConsentGroup.entries.none { type in it.required }

    private fun latestByType(userId: Long): Map<ConsentType, UserConsent> =
        repo.findAllByUserIdOrderByRecordedAtDescIdDesc(userId).groupBy { it.consentType }.mapValues { it.value.first() }

    private fun parse(given: Collection<String>): Set<ConsentType> = given.map { raw ->
        ConsentType.entries.firstOrNull { it.name == raw.uppercase() } ?: throw IllegalArgumentException("알 수 없는 동의 항목: $raw")
    }.toSet()

    private fun label(t: ConsentType) = when (t) {
        ConsentType.TERMS -> "이용약관"
        ConsentType.PRIVACY -> "개인정보 수집·이용"
        ConsentType.AGE_OVER_19 -> "만 19세 이상"
        ConsentType.MARKETING -> "마케팅 수신"
        ConsentType.BROKERAGE_DELEGATION -> "주문 권한 위임"
        ConsentType.BROKERAGE_NO_CUSTODY -> "자금 미보관 고지"
        ConsentType.BROKERAGE_LOSS_ATTRIBUTION -> "손실 귀속 고지"
    }
}
