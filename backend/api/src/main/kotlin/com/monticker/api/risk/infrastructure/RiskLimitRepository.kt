package com.monticker.api.risk.infrastructure

import com.monticker.api.risk.domain.RiskLimit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.modulith.NamedInterface
import java.util.Optional

/** matching.api.RiskController(페이퍼 트레이딩 리스크 설정 화면)에서 직접 참조하는 공개 타입. */
@NamedInterface("api")
interface RiskLimitRepository : JpaRepository<RiskLimit, Long> {
    fun findByUserId(userId: Long): Optional<RiskLimit>
}
