package com.monticker.api.risk.domain

import jakarta.persistence.*
import org.springframework.modulith.NamedInterface
import java.math.BigDecimal
import java.time.Instant

/** matching.api.RiskController(페이퍼 트레이딩 리스크 설정 화면)에서 직접 참조하는 공개 타입. */
@NamedInterface("api")
@Entity
@Table(name = "risk_limits")
class RiskLimit(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @Column(name = "daily_loss_limit_pct", nullable = false, precision = 5, scale = 2)
    var dailyLossLimitPct: BigDecimal = BigDecimal("3.00"),

    @Column(name = "concentration_limit_pct", nullable = false, precision = 5, scale = 2)
    var concentrationLimitPct: BigDecimal = BigDecimal("30.00"),

    @Column(name = "var_limit_pct", nullable = false, precision = 5, scale = 2)
    var varLimitPct: BigDecimal = BigDecimal("5.00"),

    @Column(name = "max_position_count", nullable = false)
    var maxPositionCount: Int = 10,

    @Column(name = "max_hourly_orders", nullable = false)
    var maxHourlyOrders: Int = 5,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
