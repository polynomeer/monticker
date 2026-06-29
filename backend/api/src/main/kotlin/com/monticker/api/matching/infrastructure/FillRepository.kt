package com.monticker.api.matching.infrastructure

import com.monticker.api.matching.domain.Fill
import org.springframework.data.jpa.repository.JpaRepository

interface FillRepository : JpaRepository<Fill, Long> {
    fun findAllByOrderId(orderId: Long): List<Fill>
    fun findAllByUserIdOrderByFilledAtDesc(userId: Long): List<Fill>
}
