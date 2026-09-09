package com.monticker.api.brokerage.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

enum class RebalanceTargetSource { OPTIMIZER, MANUAL }

/**
 * ADR-034 — 계좌당 활성 목표 비중 하나. weightsJson은 symbol -> weight(0~1) 맵이며
 * PortfolioOptimization.weightsJson과 같은 저장 관례를 따른다(직렬화는 서비스 레이어에서).
 * 합이 1.0 미만이면 나머지는 암묵적 현금 비중으로 취급한다.
 */
@Entity
@Table(name = "rebalance_targets")
class RebalanceTarget(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    // PortfolioOptimization.weightsJson/AlertRule.conditionJson는 columnDefinition="jsonb"만
    // 쓰고 @JdbcTypeCode가 없다 — 라이브 테스트로 확인해보니 이것만으론 INSERT 시 Hibernate가
    // varchar로 바인딩해 Postgres가 캐스트를 거부한다("column is of type jsonb but expression
    // is of type character varying"). QuantBacktestResult가 이미 쓰던 검증된 패턴을 따른다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weights_json", nullable = false, columnDefinition = "jsonb")
    var weightsJson: String,

    @Column(name = "threshold_pct", nullable = false)
    var thresholdPct: BigDecimal,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var source: RebalanceTargetSource,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
