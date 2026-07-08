package com.monticker.api.wallet.events

import com.monticker.api.wallet.domain.BehaviorGrade
import java.time.LocalDate

/**
 * BehaviorScore 재계산 후 등급이 변경되었을 때 발행되는 Spring Application Event.
 *
 * Batch 일괄 계산 트랜잭션 커밋 후(@TransactionalEventListener AFTER_COMMIT)
 * GradeChangedEventListener가 수신해 사용자에게 푸시 알림을 발송한다.
 */
data class GradeChangedEvent(
    val userId: Long,
    val scoreDate: LocalDate,
    val previousGrade: BehaviorGrade?,
    val newGrade: BehaviorGrade,
    val behaviorScore: Int,
)
