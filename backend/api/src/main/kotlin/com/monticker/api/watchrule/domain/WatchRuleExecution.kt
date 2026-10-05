package com.monticker.api.watchrule.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class WatchRuleExecutionStatus {
    /** 주문이 체결됐다. */
    EXECUTED,
    /** 리스크 게이트·잔고·보유수량 등으로 주문이 거부됐다. */
    REJECTED,
    /** 쿨다운 중이거나 강도가 하한에 못 미쳐 주문을 내지 않았다. */
    SKIPPED,
}

/**
 * 룰 발동 기록. 성공뿐 아니라 거부·건너뜀도 남긴다 — 사용자가 "왜 안 샀지"를 확인할 수 있어야 한다.
 * (watch_rule_id, stock_event_id) 유니크가 멱등 키다(V49). 퀀트 신호 발동은 (watch_rule_id, quant_signal_id)(V69).
 */
@Entity
@Table(name = "watch_rule_executions")
class WatchRuleExecution(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "watch_rule_id", nullable = false)
    val watchRuleId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    /** 이벤트 발동이면 stock_events.id. 퀀트 신호 발동이면 null이고 [quantSignalId]가 있다(V69 CHECK: 둘 중 하나). */
    @Column(name = "stock_event_id")
    val stockEventId: Long?,

    /** ADR-077 — 퀀트 신호 발동의 quant_signals.id */
    @Column(name = "quant_signal_id")
    val quantSignalId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: WatchRuleExecutionStatus,

    @Column(name = "order_id")
    val orderId: Long? = null,

    @Column(name = "fill_price", precision = 18, scale = 4)
    val fillPrice: BigDecimal? = null,

    @Column
    val quantity: Int? = null,

    @Column
    val reason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
