package com.monticker.api.subscription.infrastructure

import com.monticker.api.subscription.domain.SubscriptionStatus
import com.monticker.api.subscription.domain.UserSubscription
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.Optional

interface UserSubscriptionRepository : JpaRepository<UserSubscription, Long> {
    fun findByUserId(userId: Long): Optional<UserSubscription>

    /**
     * 갱신 배치의 리더가 부르는 메서드 (SubscriptionRenewalJobConfig).
     *
     * 시그니처가 `RepositoryItemReader`의 계약에 정확히 맞아야 한다 — 마지막 인자가
     * `Pageable`이고 반환이 `Slice`(=`Page`)여야 한다. 리더는 설정한 arguments 뒤에
     * PageRequest를 덧붙여 **리플렉션으로** 호출하므로 컴파일러는 이 계약을 검사하지 않는다.
     *
     * 원래는 `findExpiringBefore(Instant): List<...>` 였다. 그래서 배치는 매 실행마다
     * `NoSuchMethodException` → skip limit 초과 → FAILED로 끝났고, **정기결제 갱신이 단
     * 한 번도 동작한 적이 없었다.** 게다가 수동 실행 엔드포인트가 그 FAILED에도 200을
     * 돌려주고 있어 응답만 봐서는 보이지도 않았다. CH-13 카오스 실험을 처음 돌리다
     * 발견했다(ADR-053) — 이 경로에 검증이 하나도 없었다는 것이 정확히 이런 모양이다.
     */
    @Query("""
        SELECT s FROM UserSubscription s
        WHERE s.status = 'ACTIVE'
          AND s.expiresAt IS NOT NULL
          AND s.expiresAt <= :threshold
    """)
    fun findExpiringBefore(threshold: Instant, pageable: Pageable): Page<UserSubscription>
}
