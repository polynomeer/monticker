package com.monticker.api.watchrule.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class WatchRuleSide { BUY, SELL }

/** ADR-095 — 규칙 대상: 종목 하나 또는 내 관심종목 그룹(평가 시점 구성 종목마다 판정). */
enum class WatchRuleTargetType { STOCK, GROUP }

/** ADR-095 — 발동 주문 유형. LIMIT은 발동 시점 가격 × (1 + 오프셋 bps)의 지정가(ADR-074 경로). */
enum class WatchRuleOrderType { MARKET, LIMIT }

/** ADR-095 — 수량 기준: 주 수 또는 모의 계좌 평가자산의 %(발동 시점 계산, 정수 주로 내림). */
enum class WatchRuleSizeType { SHARES, EQUITY_PCT }

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

    /** 대상이 종목이면 그 종목. 그룹 규칙이면 null이고 [targetGroupId]가 있다(V92 CHECK). */
    @Column(name = "stock_id")
    val stockId: Long?,

    /** worker의 `DetectedEventType` 이름 — PRICE_SPIKE / PRICE_DROP / VOLUME_SURGE — 또는 QUANT_SIGNAL(ADR-077). */
    @Column(name = "event_type", nullable = false, length = 50)
    val eventType: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    val side: WatchRuleSide,

    /** 수량 기준이 SHARES일 때의 주 수. EQUITY_PCT면 null(V92 CHECK). */
    @Column
    var quantity: Int?,

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

    /** ADR-095 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    val targetType: WatchRuleTargetType = WatchRuleTargetType.STOCK,

    /** ADR-095 — 대상 관심종목 그룹(watchlist_groups.id, FK 없음 — 그룹 삭제 시 트리거가 규칙을 끈다). */
    @Column(name = "target_group_id")
    val targetGroupId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 6)
    val orderType: WatchRuleOrderType = WatchRuleOrderType.MARKET,

    /** ADR-095 — 지정가 오프셋(bps, ±1000). 음수 = 발동 가격 아래. LIMIT일 때만. */
    @Column(name = "limit_offset_bps")
    var limitOffsetBps: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "size_type", nullable = false, length = 10)
    val sizeType: WatchRuleSizeType = WatchRuleSizeType.SHARES,

    /** ADR-095 — 평가자산 대비 %(1~25). EQUITY_PCT일 때만. */
    @Column(name = "equity_pct", precision = 5, scale = 2)
    var equityPct: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    /** [dailyLimit]: null = 그대로, 0 = 제한 해제, 1 이상 = 새 한도. [name]: null = 그대로, 빈 문자열 = 지움. */
    fun update(
        quantity: Int?, minImportanceScore: Int?, cooldownSec: Int?, isActive: Boolean?, name: String? = null, dailyLimit: Int? = null,
        limitOffsetBps: Int? = null, equityPct: BigDecimal? = null,
    ) {
        name?.let { this.name = it.trim().ifBlank { null } }
        dailyLimit?.let { this.dailyLimit = if (it == 0) null else it }
        // 수량·오프셋·비율은 규칙의 기준(sizeType·orderType)에 맞는 값만 바꾼다 — 기준 자체는 바꾸지 않는다(서비스가 검증).
        quantity?.let { this.quantity = it }
        limitOffsetBps?.let { this.limitOffsetBps = it }
        equityPct?.let { this.equityPct = it }
        minImportanceScore?.let { this.minImportanceScore = it }
        cooldownSec?.let { this.cooldownSec = it }
        isActive?.let { this.isActive = it }
        this.updatedAt = Instant.now()
    }

    /** 이벤트 강도가 이 룰의 하한을 넘는가. */
    fun acceptsImportance(score: Int): Boolean = score >= minImportanceScore

    val isQuantSignalRule: Boolean get() = eventType == QUANT_SIGNAL

    val isGroupRule: Boolean get() = targetType == WatchRuleTargetType.GROUP

    /** 복합 조건의 동반 이벤트 유형 목록. */
    fun requiredTypes(): List<String> =
        requiredEventTypes?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        const val QUANT_SIGNAL = "QUANT_SIGNAL"
    }
}
