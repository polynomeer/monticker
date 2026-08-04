package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.PaymentRecord
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRecordRepository : JpaRepository<PaymentRecord, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<PaymentRecord>
}
