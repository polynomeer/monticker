package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.SubscriptionStatus
import com.monticker.api.subscription.domain.UserSubscription
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.Optional

interface UserSubscriptionRepository : JpaRepository<UserSubscription, Long> {
    fun findByUserId(userId: Long): Optional<UserSubscription>

    @Query("""
        SELECT s FROM UserSubscription s
        WHERE s.status = 'ACTIVE'
          AND s.expiresAt IS NOT NULL
          AND s.expiresAt <= :threshold
    """)
    fun findExpiringBefore(threshold: Instant): List<UserSubscription>
}
