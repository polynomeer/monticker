package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.UserBillingKey
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserBillingKeyRepository : JpaRepository<UserBillingKey, Long> {
    fun findByUserId(userId: Long): Optional<UserBillingKey>
    fun deleteByUserId(userId: Long)
}
