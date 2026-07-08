package com.monticker.api.batch.backfill

import java.math.BigDecimal
import java.time.LocalDate

data class CandleOhlcv(
    val stockId: Long,
    val date: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)
