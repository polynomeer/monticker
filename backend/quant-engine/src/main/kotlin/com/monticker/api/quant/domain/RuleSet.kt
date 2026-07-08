package com.monticker.api.quant.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "rule_sets")
class RuleSet(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(nullable = false)
    var version: Int = 1,

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    var status: RuleSetStatus = RuleSetStatus.DRAFT,

    @Column(name = "rule_definition", nullable = false, columnDefinition = "jsonb")
    var ruleDefinition: String,

    @Column(name = "rule_set_fingerprint", nullable = false, length = 64)
    var ruleSetFingerprint: String,

    @Column(name = "universe_json", nullable = false, columnDefinition = "jsonb")
    var universeJson: String = "{}",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
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
