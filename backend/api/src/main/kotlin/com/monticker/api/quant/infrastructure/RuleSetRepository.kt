package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.RuleSet
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface RuleSetRepository : JpaRepository<RuleSet, Long> {
    fun findAllByUserId(userId: Long): List<RuleSet>
    fun findByIdAndUserId(id: Long, userId: Long): Optional<RuleSet>
}
