package com.monticker.api.wallet.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

enum class LedgerEventType {
    // 기존 페이퍼트레이딩
    DEPOSIT, WITHDRAWAL, CASH_RESERVED, CASH_UNRESERVED, FILL, PARTIAL_FILL, FEE, SETTLEMENT,
    // 정산 시스템 (V27)
    PAPER_SETTLEMENT_COMPLETE,
    SUBSCRIPTION_PAYMENT,
    CREATOR_EARNING_CREDITED,
    CREATOR_PAYOUT_PAID,
    BROKERAGE_SETTLEMENT,
}

@Entity
@Table(name = "ledger_events")
class LedgerEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "event_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val eventType: LedgerEventType,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Column(name = "balance_after")
    val balanceAfter: BigDecimal? = null,

    @Column(name = "paper_trade_id")
    val paperTradeId: Long? = null,

    // 아웃박스(@ApplicationModuleListener) at-least-once 재전달 멱등 키. paper_trade_id 로 키를 잡을 수 없는
    // 정산완료·취소·초기화 원장에 쓴다(예: "SETTLE:{id}", "CANCEL:{orderId}", "RESET:{eventId}"). 부분 유니크(V47).
    @Column(name = "dedup_key")
    val dedupKey: String? = null,

    @Column(name = "stock_id")
    val stockId: Long? = null,

    val description: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "metadata_json", columnDefinition = "jsonb")
    val metadataJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
