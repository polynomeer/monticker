package com.monticker.api.ai

import jakarta.persistence.*
import java.time.Instant

enum class OrderProposalSide { BUY, SELL, HOLD }
enum class OrderProposalStatus { PENDING, APPROVED, REJECTED }

/**
 * ADR-036 — AI가 생성한 주문 "제안". 제안 ≠ 주문: 여기서는 방향과 근거만 기록하고,
 * 실제 주문 제출은 프론트가 별도로 리스크 게이트가 있는 /api/matching/orders를 거친다.
 */
@Entity
@Table(name = "order_proposals")
class OrderProposal(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    val side: OrderProposalSide,

    @Column(nullable = false, columnDefinition = "TEXT")
    val reasoning: String,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: OrderProposalStatus = OrderProposalStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
) {
    fun approve() {
        check(status == OrderProposalStatus.PENDING) { "이미 처리된 제안은 승인 불가합니다" }
        check(Instant.now().isBefore(expiresAt)) { "만료된 제안은 승인 불가합니다" }
        status = OrderProposalStatus.APPROVED
        decidedAt = Instant.now()
    }

    fun reject() {
        check(status == OrderProposalStatus.PENDING) { "이미 처리된 제안은 거부 불가합니다" }
        status = OrderProposalStatus.REJECTED
        decidedAt = Instant.now()
    }
}
