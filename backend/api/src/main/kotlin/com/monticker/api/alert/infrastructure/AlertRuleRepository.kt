package com.monticker.api.alert.infrastructure

import com.monticker.api.alert.domain.AlertRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AlertRuleRepository : JpaRepository<AlertRule, Long> {
    fun findAllByUserIdAndIsActiveTrue(userId: Long): List<AlertRule>

    /** 삭제하지 않은 규칙(켜짐·꺼짐 모두) */
    fun findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId: Long): List<AlertRule>

    /**
     * ADR-073 — 켜기/끄기. 엔티티를 읽어 save()하면 그 사이 삭제된 규칙의 deleted_at을 null로 되돌려
     * 되살릴 수 있다 — 조건부 UPDATE 한 문장으로 "내 것이고 삭제되지 않았을 때만" 바꾼다. 바뀐 행 수를 돌려준다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AlertRule r SET r.isActive = :active, r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.id = :id AND r.userId = :userId AND r.deletedAt IS NULL
    """)
    fun updateActive(@Param("id") id: Long, @Param("userId") userId: Long, @Param("active") active: Boolean): Int

    /** 삭제 — 꺼지고 deleted_at이 남는다(이미 삭제됐으면 시각을 유지). 바뀐 행 수를 돌려준다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AlertRule r SET r.isActive = false, r.deletedAt = COALESCE(r.deletedAt, CURRENT_TIMESTAMP), r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.id = :id AND r.userId = :userId
    """)
    fun markDeleted(@Param("id") id: Long, @Param("userId") userId: Long): Int
}
