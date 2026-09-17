package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
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

    /** 멱등성: 같은 체결의 같은 유형 원장이 이미 있으면 아웃박스 재전달(@ApplicationModuleListener 재시도)을 무시한다. */
    fun existsByPaperTradeIdAndEventType(paperTradeId: Long, eventType: LedgerEventType): Boolean

    /** 멱등성(정산완료·취소·초기화): 같은 아웃박스 이벤트 재전달을 dedup_key 로 무시한다. */
    fun existsByDedupKey(dedupKey: String): Boolean
}
