package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.EmotionTag
import org.springframework.data.jpa.repository.JpaRepository

interface EmotionTagRepository : JpaRepository<EmotionTag, Long> {
    fun findByPaperTradeId(paperTradeId: Long): EmotionTag?
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<EmotionTag>
    /** 리플레이의 감정 칩 — 거래 여러 건의 태그를 한 번에. 태그 소유자로도 거른다(예전 IDOR 잔여 행 차단). */
    fun findAllByUserIdAndPaperTradeIdIn(userId: Long, paperTradeIds: Collection<Long>): List<EmotionTag>
}
