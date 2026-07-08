package com.monticker.api.matching.infrastructure

import com.monticker.api.matching.domain.RiskLimit
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface RiskLimitRepository : JpaRepository<RiskLimit, Long> {
    fun findByUserId(userId: Long): Optional<RiskLimit>
}
