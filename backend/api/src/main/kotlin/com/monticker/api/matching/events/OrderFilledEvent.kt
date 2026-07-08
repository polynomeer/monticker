package com.monticker.api.matching.events

import org.springframework.modulith.events.Externalized
import java.math.BigDecimal
import java.time.Instant

/**
 * 주문 체결 완료 이벤트.
 * matching 모듈이 발행 → wallet(원장), quant(전략 성과) 모듈이 구독.
 *
 * @Externalized: Spring Modulith 이벤트 퍼블리케이션 스토어에 기록.
 * 트랜잭션 커밋 후 비동기/동기 리스너에게 전달.
 */
@Externalized
data class OrderFilledEvent(
    val orderId:   Long,
    val userId:    Long,
    val stockId:   Long,
    val fillId:    Long,
    val side:      String,          // "BUY" | "SELL"
    val quantity:  Int,
    val fillPrice: BigDecimal,
    val amount:    BigDecimal,
    val filledAt:  Instant = Instant.now(),
)
