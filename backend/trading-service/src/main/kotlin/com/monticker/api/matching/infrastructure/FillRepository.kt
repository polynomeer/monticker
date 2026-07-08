package com.monticker.api.matching.infrastructure

import com.monticker.api.matching.domain.Fill
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Write Side — Order Aggregate 경계 내 Fill 접근만 허용.
 * userId 기반 조회는 FillQueryService(CQRS Read Side)에서 처리한다.
 */
interface FillRepository : JpaRepository<Fill, Long> {
    fun findAllByOrderId(orderId: Long): List<Fill>
}
