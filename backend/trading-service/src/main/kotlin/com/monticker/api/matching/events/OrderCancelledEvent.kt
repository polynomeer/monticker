package com.monticker.api.matching.events

import java.math.BigDecimal
import java.time.Instant

data class OrderCancelledEvent(
    val orderId:      Long,
    val userId:       Long,
    val stockId:      Long,
    val side:         String,
    val refundAmount: BigDecimal,
    val cancelledAt:  Instant = Instant.now(),
)
