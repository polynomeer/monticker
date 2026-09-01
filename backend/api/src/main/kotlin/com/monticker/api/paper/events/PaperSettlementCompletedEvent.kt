package com.monticker.api.paper.events

import java.math.BigDecimal

data class PaperSettlementCompletedEvent(
    val userId: Long,
    val settlementId: Long,
    val stockId: Long,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val balanceAfter: BigDecimal,
)
