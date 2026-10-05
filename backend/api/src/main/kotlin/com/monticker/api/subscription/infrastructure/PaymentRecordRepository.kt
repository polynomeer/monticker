package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.PaymentRecord
import com.monticker.api.subscription.domain.PaymentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional

interface PaymentRecordRepository : JpaRepository<PaymentRecord, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<PaymentRecord>

    /** ADR-053 — (구독, 청구주기)에서 유도된 orderId로 이전 시도를 찾는다. */
    fun findByPgOrderId(pgOrderId: String): Optional<PaymentRecord>

    fun findFirstByUserIdAndStatusOrderByCreatedAtDesc(
        userId: Long,
        status: PaymentStatus,
    ): Optional<PaymentRecord>

    /**
     * 마지막 성공 이후의 실패 건수 = 연속 실패 횟수.
     *
     * 예전에는 "최근 3건 중 FAILED 개수"를 셌는데, 그러면 불확정(PENDING)으로 남은 시도가
     * 창을 밀어내 실제 연속 실패와 어긋난다. 기준점을 마지막 성공으로 잡으면 PENDING이
     * 몇 건 끼어도 판정이 흔들리지 않는다 (ADR-053).
     */
    fun countByUserIdAndStatusAndCreatedAtAfter(
        userId: Long,
        status: PaymentStatus,
        after: Instant,
    ): Long

    /**
     * ADR-059 — 적체 청소 배치가 집어갈 PENDING 결제.
     *
     * `pg_order_id` 가 있어야만 PG 에 되물을 수 있다(그게 유일한 열쇠다). 방금 만들어진 건은
     * 아직 확정 중일 수 있으므로 제외한다 — 진행 중인 결제를 배치가 가로채면 안 된다.
     *
     * 정리되면 PENDING 에서 빠지므로 `id > afterId` 키셋으로 읽는다([com.monticker.api.batch.KeysetItemReader]).
     */
    fun findAllByStatusAndPgOrderIdIsNotNullAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
        status: PaymentStatus,
        before: Instant,
        afterId: Long,
        pageable: Pageable,
    ): List<PaymentRecord>

    fun countByStatus(status: PaymentStatus): Long
}
