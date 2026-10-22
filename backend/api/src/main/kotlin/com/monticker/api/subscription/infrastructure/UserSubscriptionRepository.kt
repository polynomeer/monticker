package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.SubscriptionStatus
import com.monticker.api.subscription.domain.UserSubscription
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional

interface UserSubscriptionRepository : JpaRepository<UserSubscription, Long> {
    fun findByUserId(userId: Long): Optional<UserSubscription>

    /**
     * 갱신·해지·탈퇴를 구독 행 하나로 직렬화한다 (ADR-053·ADR-109 Note). 갱신이 빌링키를 읽고 청구하는 동안 해지·탈퇴가 끼어들어
     * 커밋되면 한 번 더 청구된다 — 세 경로가 모두 이 락을 먼저 쥔다.
     *
     * 이미 영속성 컨텍스트에 있는 엔티티는 락만 걸리고 상태는 갱신되지 않는다(JPA 규약). 갱신 배치는 청크 트랜잭션에서 읽은 엔티티를
     * 들고 오므로 호출부가 `EntityManager.refresh`로 다시 읽어야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSubscription s WHERE s.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<UserSubscription>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSubscription s WHERE s.userId = :userId")
    fun findByUserIdForUpdate(@Param("userId") userId: Long): Optional<UserSubscription>

    /**
     * 갱신 배치의 리더가 부르는 메서드 (SubscriptionRenewalJobConfig, [com.monticker.api.batch.KeysetItemReader]).
     *
     * 갱신되면 `expiresAt`이 밀리고 다운그레이드되면 `status`가 바뀌어 조건에서 빠지므로 offset이 아니라
     * `id > afterId` 키셋으로 읽는다. 예전 `RepositoryItemReader` 시절에는 시그니처가 리더 계약과 어긋나
     * 갱신 잡이 한 번도 돌지 않은 적이 있다(ADR-053, CH-13) — 이제 리더가 람다로 직접 호출하므로
     * 컴파일러가 시그니처를 검사한다.
     */
    @Query("""
        SELECT s FROM UserSubscription s
        WHERE s.status = 'ACTIVE'
          AND s.expiresAt IS NOT NULL
          AND s.expiresAt <= :threshold
          AND s.id > :afterId
        ORDER BY s.id ASC
    """)
    fun findExpiringBeforeAfter(threshold: Instant, afterId: Long, pageable: Pageable): List<UserSubscription>
}
