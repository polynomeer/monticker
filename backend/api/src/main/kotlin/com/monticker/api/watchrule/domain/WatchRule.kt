package com.monticker.api.watchrule.domain

import jakarta.persistence.*
import java.time.Instant

enum class WatchRuleSide { BUY, SELL }

/**
 * ADR-051 — "이 종목에 이런 이벤트가 감지되면 모의투자 계좌로 N주 매수/매도한다".
 *
 * 모의투자 계좌 전용이다. 실브로커 계좌의 자동 실행은 의도적으로 범위 밖이다 — ADR-025(실주문은 사전
 * 리스크 게이트를 반드시 통과)와 ADR-036(AI·자동화는 제안까지, 제출은 사람이)이 세운 선을 넘기 때문이다.
 */
@Entity
@Table(name = "watch_rules")
class WatchRule(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    /** worker의 `DetectedEventType` 이름 — PRICE_SPIKE / PRICE_DROP / VOLUME_SURGE. */
    @Column(name = "event_type", nullable = false, length = 50)
    val eventType: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    val side: WatchRuleSide,

    @Column(nullable = false)
    var quantity: Int,

    /** 이벤트 importance_score 가 이 값 미만이면 발동하지 않는다. */
    @Column(name = "min_importance_score", nullable = false)
    var minImportanceScore: Int = 0,

    /** 직전 발동으로부터 이 시간 안에는 다시 발동하지 않는다. */
    @Column(name = "cooldown_sec", nullable = false)
    var cooldownSec: Int = 600,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun update(quantity: Int?, minImportanceScore: Int?, cooldownSec: Int?, isActive: Boolean?) {
        quantity?.let { this.quantity = it }
        minImportanceScore?.let { this.minImportanceScore = it }
        cooldownSec?.let { this.cooldownSec = it }
        isActive?.let { this.isActive = it }
        this.updatedAt = Instant.now()
    }

    /** 이벤트 강도가 이 룰의 하한을 넘는가. */
    fun acceptsImportance(score: Int): Boolean = score >= minImportanceScore
}
