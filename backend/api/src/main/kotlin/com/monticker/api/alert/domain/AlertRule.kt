package com.monticker.api.alert.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "alert_rules")
class AlertRule(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id")
    val stockId: Long? = null,

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val ruleType: AlertRuleType,

    @Column(nullable = false, columnDefinition = "JSONB")
    val conditionJson: String,

    @Column(nullable = false)
    var isActive: Boolean = true,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun deactivate() {
        isActive = false
        updatedAt = Instant.now()
    }

    fun activate() {
        isActive = true
        updatedAt = Instant.now()
    }
}

enum class AlertRuleType {
    PRICE_ABOVE, PRICE_BELOW, VOLUME_SURGE, NEWS_PUBLISHED, DISCLOSURE_PUBLISHED
}
