package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.SubscriptionStatus
import com.monticker.api.subscription.domain.UserSubscription
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.Optional

interface UserSubscriptionRepository : JpaRepository<UserSubscription, Long> {
    fun findByUserId(userId: Long): Optional<UserSubscription>

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
