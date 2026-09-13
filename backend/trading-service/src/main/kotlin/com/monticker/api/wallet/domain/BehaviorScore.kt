package com.monticker.api.wallet.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "investment_behavior_scores")
class BehaviorScore(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "score_date", nullable = false)
    val scoreDate: LocalDate,

    @Column(name = "behavior_score")
    val behaviorScore: Int? = null,

    @Column(name = "survival_score")
    val survivalScore: Int? = null,

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "score_breakdown", columnDefinition = "jsonb")
    val scoreBreakdown: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "feedback_json", columnDefinition = "jsonb")
    val feedbackJson: String? = null,

    @Column(name = "grade", length = 20)
    @Enumerated(EnumType.STRING)
    val grade: BehaviorGrade? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
