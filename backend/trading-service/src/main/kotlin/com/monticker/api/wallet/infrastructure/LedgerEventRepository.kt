package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.LedgerEvent
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

// backend/api의 복사본(MSA 분리 잔재) — ADR-043 변경을 양쪽에 동일하게 적용한다.
interface LedgerEventRepository : JpaRepository<LedgerEvent, Long> {

    /** ADR-043 커서 페이징. cursor = 이전 페이지 마지막 id, 첫 페이지는 Long.MAX_VALUE. 정렬 키는 id(단조 증가). */
    @Query("SELECT e FROM LedgerEvent e WHERE e.userId = :userId AND e.id < :cursor ORDER BY e.id DESC")
    fun findPage(userId: Long, cursor: Long, pageable: Pageable): List<LedgerEvent>

    fun findAllByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId: Long, from: Instant, to: Instant): List<LedgerEvent>

    fun findTopByPaperTradeIdOrderByIdDesc(paperTradeId: Long): LedgerEvent?
}
