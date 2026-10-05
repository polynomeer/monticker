package com.monticker.api.matching.infrastructure

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import java.time.Instant

interface OrderRepository : JpaRepository<Order, Long> {
    fun findByUserIdAndStatusIn(userId: Long, statuses: List<OrderStatus>): List<Order>
    fun findByStockIdAndStatusIn(stockId: Long, statuses: List<OrderStatus>): List<Order>
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<Order>
    fun countByUserIdAndCreatedAtAfter(userId: Long, after: Instant): Long

    /** ADR-051 — 멱등 재제출 판정. 키가 있으면 새 주문을 만들지 않고 이 주문을 돌려준다. */
    fun findByIdempotencyKey(idempotencyKey: String): Order?

    /**
     * 취소처럼 "상태를 읽고 → 돈을 돌려주는" 흐름용. 행을 FOR UPDATE로 잡아 같은 주문에 대한 동시 요청을
     * 직렬화한다 — 둘 다 PENDING을 보고 둘 다 환불하던 이중 환불을 막는다(2026-10 설계 리뷰).
     * 엔티티 이름 `Order`가 JPQL 예약어라 문자열 @Query 대신 파생 쿼리를 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): Order?
}
