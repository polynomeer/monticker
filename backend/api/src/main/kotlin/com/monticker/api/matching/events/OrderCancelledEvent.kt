package com.monticker.api.matching.events

import org.springframework.modulith.events.Externalized
import java.math.BigDecimal
import java.time.Instant

@Externalized
data class OrderCancelledEvent(
    val orderId:      Long,
    val userId:       Long,
    val stockId:      Long,
    val side:         String,
    val refundAmount: BigDecimal,
    val cancelledAt:  Instant = Instant.now(),
)
