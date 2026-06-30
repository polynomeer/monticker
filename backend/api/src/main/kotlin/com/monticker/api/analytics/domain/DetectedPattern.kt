package com.monticker.api.analytics.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "detected_patterns")
class DetectedPattern(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(name = "pattern_type", nullable = false, length = 30)
    val patternType: String,

    @Column(name = "confidence_score", nullable = false)
    val confidenceScore: Int,

    @Column(name = "swing_points_json", nullable = false, columnDefinition = "jsonb")
    val swingPointsJson: String,

    @Column(name = "detected_at", nullable = false)
    val detectedAt: Instant,

    @Column(name = "candle_from", nullable = false)
    val candleFrom: Instant,

    @Column(name = "candle_to", nullable = false)
    val candleTo: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
