package com.monticker.api.alert.infrastructure

import com.monticker.api.alert.domain.AlertRule
import org.springframework.data.jpa.repository.JpaRepository

interface AlertRuleRepository : JpaRepository<AlertRule, Long> {
    fun findAllByUserIdAndIsActiveTrue(userId: Long): List<AlertRule>
}
