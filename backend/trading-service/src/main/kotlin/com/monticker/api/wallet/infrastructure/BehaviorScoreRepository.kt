package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.BehaviorScore
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.Optional

interface BehaviorScoreRepository : JpaRepository<BehaviorScore, Long> {
    fun findByUserIdAndScoreDate(userId: Long, scoreDate: LocalDate): Optional<BehaviorScore>
}
