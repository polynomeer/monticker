package com.monticker.api.watchrule.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

enum class WatchRuleExecutionStatus {
    /** 주문이 체결됐다. */
    EXECUTED,
    /** ADR-095 — 지정가 주문이 접수됐지만 아직 미체결(이후 체결은 스위퍼, ADR-074). 발동으로 센다. */
    PLACED,
    /** ADR-098 — PLACED였던 지정가가 이후 체결됐다(스위퍼). fill_price·resolved_at이 채워진다. */
    FILLED,
    /** ADR-098 — PLACED였던 지정가가 체결 전에 취소됐다(사용자·체결 시점 리스크·보유 부족). reason·resolved_at. */
    CANCELLED,
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

    /** ADR-095 — 발동 종목. 그룹 규칙은 발동마다 다르다. V92 이전 기록은 규칙의 종목으로 백필. */
    @Column(name = "stock_id")
    val stockId: Long? = null,

    /** ADR-095 — 지정가 접수(PLACED)의 지정가. */
    @Column(name = "limit_price", precision = 18, scale = 4)
    val limitPrice: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    /** ADR-098 — PLACED 이후 결과(FILLED·CANCELLED)가 정해진 시각. 결과 전이는 JDBC 조건부 UPDATE로만 한다(WatchRuleOrderOutcomes). */
    @Column(name = "resolved_at")
    val resolvedAt: Instant? = null,
)
