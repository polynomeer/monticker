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

    /** worker의 `DetectedEventType` 이름 — PRICE_SPIKE / PRICE_DROP / VOLUME_SURGE — 또는 QUANT_SIGNAL(ADR-077). */
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

    /** ADR-077 — 사용자가 붙인 규칙 이름(없으면 화면이 종목·조건으로 만든다). */
    @Column(length = 100)
    var name: String? = null,

    /** ADR-077 — eventType = QUANT_SIGNAL일 때 이 전략(룰셋)의 신호에 발동한다. */
    @Column(name = "rule_set_id", length = 24)
    val ruleSetId: String? = null,

    /** ADR-077 — QUANT_SIGNAL 규칙이 반응하는 신호 방향(BUY·SELL). */
    @Column(name = "signal_direction", length = 4)
    val signalDirection: String? = null,

    /** ADR-077 — 복합 조건: 주 이벤트 앞 [conditionWindowSec] 안에 함께 감지됐어야 하는 이벤트 유형(쉼표 구분). */
    @Column(name = "required_event_types", length = 200)
    val requiredEventTypes: String? = null,

    @Column(name = "condition_window_sec")
    val conditionWindowSec: Int? = null,

    /** ADR-077 — 하루(KST) 최대 체결 횟수. null이면 제한 없음. */
    @Column(name = "daily_limit")
    var dailyLimit: Int? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    /** [dailyLimit]: null = 그대로, 0 = 제한 해제, 1 이상 = 새 한도. [name]: null = 그대로, 빈 문자열 = 지움. */
    fun update(quantity: Int?, minImportanceScore: Int?, cooldownSec: Int?, isActive: Boolean?, name: String? = null, dailyLimit: Int? = null) {
        name?.let { this.name = it.trim().ifBlank { null } }
        dailyLimit?.let { this.dailyLimit = if (it == 0) null else it }
        quantity?.let { this.quantity = it }
        minImportanceScore?.let { this.minImportanceScore = it }
        cooldownSec?.let { this.cooldownSec = it }
        isActive?.let { this.isActive = it }
        this.updatedAt = Instant.now()
    }

    /** 이벤트 강도가 이 룰의 하한을 넘는가. */
    fun acceptsImportance(score: Int): Boolean = score >= minImportanceScore

    val isQuantSignalRule: Boolean get() = eventType == QUANT_SIGNAL

    /** 복합 조건의 동반 이벤트 유형 목록. */
    fun requiredTypes(): List<String> =
        requiredEventTypes?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        const val QUANT_SIGNAL = "QUANT_SIGNAL"
    }
}
