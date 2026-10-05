package com.monticker.api.risk.infrastructure

import com.monticker.api.risk.domain.RiskLimit
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

/** risk 모듈 내부 전용 — 한도 쓰기는 RiskLimitService(ADR-069 쿨링오프)만 한다. 다른 모듈은 RiskLimitService를 쓴다. */
interface RiskLimitRepository : JpaRepository<RiskLimit, Long> {
    fun findByUserId(userId: Long): Optional<RiskLimit>

    /** ADR-069 — 한도 수정·승격을 사용자별로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RiskLimit r WHERE r.userId = :userId")
    fun findForUpdate(@Param("userId") userId: Long): Optional<RiskLimit>
}
