package com.monticker.worker.kafka

import java.math.BigDecimal

data class TickProcessedMessage(val stockId: Long, val price: BigDecimal)
