package com.monticker.api.marketdata.domain

import java.math.BigDecimal
import java.time.Instant

data class Candle(
    val stockId: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val time: Instant,
)
