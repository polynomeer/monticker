package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.QuantForwardTestEquityPoint
import org.springframework.data.jpa.repository.JpaRepository

interface QuantForwardTestEquityRepository : JpaRepository<QuantForwardTestEquityPoint, Long> {
    fun findAllByForwardTestIdOrderByEvalDateAsc(forwardTestId: Long): List<QuantForwardTestEquityPoint>
}
