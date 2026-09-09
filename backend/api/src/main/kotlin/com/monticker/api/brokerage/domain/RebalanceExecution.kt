package com.monticker.api.brokerage.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class RebalanceExecutionStatus { EXECUTING, COMPLETED, PARTIALLY_FAILED }
enum class RebalanceLegStatus { EXECUTED, FAILED }

@Entity
@Table(name = "rebalance_executions")
class RebalanceExecution(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: RebalanceExecutionStatus = RebalanceExecutionStatus.EXECUTING,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,
) {
    fun complete(anyFailed: Boolean) {
        status = if (anyFailed) RebalanceExecutionStatus.PARTIALLY_FAILED else RebalanceExecutionStatus.COMPLETED
        completedAt = Instant.now()
    }
}

@Entity
@Table(name = "rebalance_execution_legs")
class RebalanceExecutionLeg(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false)
    val symbol: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val side: OrderSide,

    @Column(name = "target_weight", nullable = false)
    val targetWeight: BigDecimal,

    @Column(name = "current_weight", nullable = false)
    val currentWeight: BigDecimal,

    @Column(name = "diff_pct", nullable = false)
    val diffPct: BigDecimal,

    @Column(nullable = false)
    val quantity: Int,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val status: RebalanceLegStatus,

    @Column(name = "executed_order_id")
    val executedOrderId: Long? = null,

    @Column(name = "fail_reason")
    val failReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
