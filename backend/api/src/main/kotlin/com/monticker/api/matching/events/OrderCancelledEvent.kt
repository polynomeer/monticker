package com.monticker.api.matching.events

import org.springframework.modulith.events.Externalized
import java.math.BigDecimal
import java.time.Instant

@Externalized("trading.order-cancelled::#{#this.userId}")
data class OrderCancelledEvent(
    val orderId:      Long,
    val userId:       Long,
    val stockId:      Long,
    val side:         String,
    val refundAmount: BigDecimal,
    val cancelledAt:  Instant = Instant.now(),
    /** ADR-098 — 취소 사유(사용자 취소·체결 시점 리스크 차단·보유 부족). 이 필드 추가 전에 직렬화된 아웃박스 이벤트는 null. */
    val reason:       String? = null,
)
