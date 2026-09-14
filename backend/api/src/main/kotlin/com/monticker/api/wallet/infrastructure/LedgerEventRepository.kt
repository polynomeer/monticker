package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.LedgerEvent
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface LedgerEventRepository : JpaRepository<LedgerEvent, Long> {

    /**
     * ADR-043 커서 페이징. cursor = 이전 페이지 마지막 id, 첫 페이지는 Long.MAX_VALUE.
     * 정렬 키가 id인 이유: created_at은 같은 트랜잭션에서 만들어진 이벤트끼리 동일할 수 있어
     * 커서가 행을 건너뛰거나 중복시킨다. id는 BIGSERIAL이라 단조 증가한다.
     * 인덱스: idx_ledger_events_user_id_desc (user_id, id DESC).
     */
    @Query("SELECT e FROM LedgerEvent e WHERE e.userId = :userId AND e.id < :cursor ORDER BY e.id DESC")
    fun findPage(userId: Long, cursor: Long, pageable: Pageable): List<LedgerEvent>

    fun findAllByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId: Long, from: Instant, to: Instant): List<LedgerEvent>

    /** 영수증: 거래 1건의 최신 원장 행. 이전엔 findAll()로 전 유저 원장을 힙에 올려 걸렀다. */
    fun findTopByPaperTradeIdOrderByIdDesc(paperTradeId: Long): LedgerEvent?
}
