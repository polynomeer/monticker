package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.PlanCode
import com.monticker.api.subscription.domain.SubscriptionPlan
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface SubscriptionPlanRepository : JpaRepository<SubscriptionPlan, Long> {
    fun findByCode(code: PlanCode): Optional<SubscriptionPlan>
    fun findAllByIsActiveTrue(): List<SubscriptionPlan>
}
