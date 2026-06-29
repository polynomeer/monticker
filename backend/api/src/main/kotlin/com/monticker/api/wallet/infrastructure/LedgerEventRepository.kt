package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.LedgerEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface LedgerEventRepository : JpaRepository<LedgerEvent, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<LedgerEvent>
    fun findAllByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId: Long, from: Instant, to: Instant): List<LedgerEvent>
}
