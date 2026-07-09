package com.monticker.api.matching.events

import org.springframework.modulith.events.Externalized
import java.math.BigDecimal
import java.time.Instant

/**
 * 주문 체결 완료 이벤트.
 * matching 모듈이 발행 → wallet(원장), quant(전략 성과) 모듈이 구독.
 *
 * @Externalized("topic::keyExpr"):
 *   - event_publication 테이블에 트랜잭션 내 INSERT (Outbox)
 *   - 커밋 후 spring-modulith-events-kafka가 trading.order-filled 토픽에 발행
 *   - 미발행 이벤트는 OutboxResubmissionConfig가 5분마다 재전송
 */
@Externalized("trading.order-filled::#{#this.userId}")
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
