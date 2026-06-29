package com.monticker.api.wallet.domain

import jakarta.persistence.*
import java.time.Instant

enum class EmotionType {
    CONFIDENT, ANXIOUS, FOLLOWING, NEWS_BASED, FOMO, LONG_TERM, INTUITION, REBALANCING, AVERAGING_DOWN, OTHER
}

@Entity
@Table(name = "order_emotion_tags")
class EmotionTag(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "paper_trade_id", nullable = false, unique = true)
    val paperTradeId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val emotion: EmotionType,

    val memo: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
