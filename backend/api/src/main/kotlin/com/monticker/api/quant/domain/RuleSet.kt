package com.monticker.api.quant.domain

import java.time.Instant

// V22 마이그레이션으로 rule_sets 테이블이 MongoDB로 이전됨 — RuleSetDocument 사용
class RuleSet(
    val id: Long = 0,
    val userId: Long,
    var name: String,
    var description: String? = null,
    var version: Int = 1,
    var status: RuleSetStatus = RuleSetStatus.DRAFT,
    var ruleDefinition: String,
    var ruleSetFingerprint: String,
    var universeJson: String = "{}",
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

    fun updateDefinition(newJson: String, fingerprint: String) {
        ruleDefinition = newJson
        ruleSetFingerprint = fingerprint
        version += 1
        updatedAt = Instant.now()
    }

    fun markBacktested() {
        status = RuleSetStatus.BACKTESTED
        updatedAt = Instant.now()
    }

    fun publish() {
        require(status == RuleSetStatus.BACKTESTED) { "백테스트 완료 후 배포할 수 있습니다: 현재 상태 $status" }
        status = RuleSetStatus.RUNNING
        updatedAt = Instant.now()
    }
}
