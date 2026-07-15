package com.monticker.api.quant.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * RuleSet MongoDB 도큐먼트.
 *
 * ruleDefinition과 universeJson을 Map으로 직접 저장해 스키마 변경에 자유롭다.
 * 버전 히스토리는 versions 리스트에 embed — rule_set_versions 테이블 대체.
 */
@Document(collection = "rule_sets")
data class RuleSetDocument(
    @Id
    val id: String? = null,

    @Indexed
    val userId: Long,

    var name: String,
    var description: String? = null,
    var version: Int = 1,
    var status: String = RuleSetStatus.DRAFT.name,

    /** 룰 정의 — 조건 트리, positionSizing 등 자유 형태 JSON */
    var ruleDefinition: Map<String, Any> = emptyMap(),

    /** 백테스트 / 포워드 테스트 대상 종목 유니버스 */
    var universeJson: Map<String, Any> = emptyMap(),

    var ruleSetFingerprint: String = "",

    /** 버전별 스냅샷 (최근 50개까지 보관) */
    val versions: MutableList<RuleVersionEntry> = mutableListOf(),

    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun rename(newName: String) {
        name = newName
        updatedAt = Instant.now()
    }

    fun updateDescription(newDescription: String?) {
        description = newDescription
        updatedAt = Instant.now()
    }

    fun updateDefinition(newDef: Map<String, Any>, fingerprint: String, summary: String? = null) {
        snapshotCurrentVersion(summary)
        ruleDefinition = newDef
        ruleSetFingerprint = fingerprint
        version += 1
        updatedAt = Instant.now()
    }

    fun markBacktested() {
        status = RuleSetStatus.BACKTESTED.name
        updatedAt = Instant.now()
    }

    fun publish() {
        require(status == RuleSetStatus.BACKTESTED.name) {
            "백테스트 완료 후 배포할 수 있습니다: 현재 상태 $status"
        }
        status = RuleSetStatus.RUNNING.name
        updatedAt = Instant.now()
    }

    private fun snapshotCurrentVersion(summary: String?) {
        versions.add(
            RuleVersionEntry(
                version          = version,
                ruleDefinition   = ruleDefinition,
                fingerprint      = ruleSetFingerprint,
                changeSummary    = summary,
                createdAt        = Instant.now(),
            )
        )
        // 최근 50개만 보관
        if (versions.size > 50) versions.removeAt(0)
    }
}

data class RuleVersionEntry(
    val version: Int,
    val ruleDefinition: Map<String, Any>,
    val fingerprint: String,
    val changeSummary: String?,
    val createdAt: Instant,
)
