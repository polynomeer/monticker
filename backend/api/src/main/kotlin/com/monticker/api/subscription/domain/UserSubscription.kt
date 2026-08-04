package com.monticker.api.subscription.domain

import jakarta.persistence.*
import java.time.Instant

enum class SubscriptionStatus { ACTIVE, EXPIRED, CANCELLED }

@Entity
@Table(name = "user_subscriptions")
class UserSubscription(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    var plan: SubscriptionPlan,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: SubscriptionStatus = SubscriptionStatus.ACTIVE,

    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun upgrade(newPlan: SubscriptionPlan, expiresAt: Instant) {
        this.plan = newPlan
        this.status = SubscriptionStatus.ACTIVE
        this.expiresAt = expiresAt
        this.cancelledAt = null
        this.updatedAt = Instant.now()
    }

    fun cancel() {
        this.status = SubscriptionStatus.CANCELLED
        this.cancelledAt = Instant.now()
        this.updatedAt = Instant.now()
    }

    fun expire() {
        this.status = SubscriptionStatus.EXPIRED
        this.updatedAt = Instant.now()
    }

    fun downgradeToFree(freePlan: SubscriptionPlan) {
        this.plan = freePlan
        this.status = SubscriptionStatus.ACTIVE
        this.expiresAt = null
        this.cancelledAt = null
        this.updatedAt = Instant.now()
    }
}
