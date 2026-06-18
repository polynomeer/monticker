package com.monticker.api.event.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "stock_events")
class StockEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val eventType: EventType,

    @Column(nullable = false, length = 300)
    val title: String,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Column(nullable = false)
    val eventTime: Instant,

    @Column(nullable = false)
    val importanceScore: Int = 0,

    @Column(precision = 5, scale = 4)
    val sentimentScore: BigDecimal? = null,

    @Column(length = 50)
    val sourceType: String? = null,

    val sourceId: Long? = null,

    @Column(columnDefinition = "JSONB")
    val metadataJson: String? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    val updatedAt: Instant = Instant.now(),
)
