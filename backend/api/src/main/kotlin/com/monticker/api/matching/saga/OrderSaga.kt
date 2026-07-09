package com.monticker.api.matching.saga

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class SagaStep {
    INIT,
    VALIDATED,
    CASH_RESERVED,
    ORDER_CREATED,
    ORDER_FILLED,
    CASH_SETTLED,
    COMPLETED,

    // 보상 완료 단계
    COMPENSATING,
    COMPENSATED,
    FAILED,
}

@Entity
@Table(name = "order_sagas")
class OrderSaga(
    @Id
    val id: UUID = UUID.randomUUID(),

    val userId: Long,

    @Column(name = "order_id")
    var orderId: Long? = null,

    val stockId: Long,
    val side: String,
    val quantity: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step")
    var currentStep: SagaStep = SagaStep.INIT,

    @Enumerated(EnumType.STRING)
    var status: SagaStatus = SagaStatus.STARTED,

    /** BUY 주문 시 예약 차감된 현금 금액 (보상 트랜잭션에서 환불) */
    var reservedAmount: BigDecimal? = null,

    var errorMessage: String? = null,

    val startedAt: Instant = Instant.now(),
    var completedAt: Instant? = null,
    var compensatedAt: Instant? = null,
)

enum class SagaStatus { STARTED, COMPLETED, COMPENSATING, COMPENSATED, FAILED }
