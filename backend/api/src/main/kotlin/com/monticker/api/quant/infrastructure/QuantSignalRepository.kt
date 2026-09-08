package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.QuantSignal
import org.springframework.data.jpa.repository.JpaRepository

interface QuantSignalRepository : JpaRepository<QuantSignal, Long> {
    fun findAllByForwardTestIdOrderBySignalTimeDesc(forwardTestId: Long): List<QuantSignal>
}
