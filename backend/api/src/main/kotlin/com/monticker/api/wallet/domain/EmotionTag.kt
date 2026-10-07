package com.monticker.api.wallet.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * 주문 감정 태그. DB 값은 이름 그대로(order_emotion_tags.emotion VARCHAR(30), CHECK 없음).
 * ADR-085 — PLANNED("계획대로")·IMPATIENT("조급함") 추가. PLANNED는 리플레이의 "계획된 주문" 판정에도 쓰인다.
 */
enum class EmotionType {
    CONFIDENT, ANXIOUS, FOLLOWING, NEWS_BASED, FOMO, LONG_TERM, INTUITION, REBALANCING, AVERAGING_DOWN, OTHER,
    PLANNED, IMPATIENT;

    companion object {
        /** 행동 점수의 "감정적 충동 거래" — 하나라도 있으면 +10 보너스가 빠진다. FOMO는 따로 감점한다. */
        val IMPULSIVE = setOf(FOMO, ANXIOUS, IMPATIENT)
    }
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
