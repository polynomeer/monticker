package com.monticker.api.wallet.infrastructure

import com.monticker.api.wallet.domain.EmotionTag
import org.springframework.data.jpa.repository.JpaRepository

interface EmotionTagRepository : JpaRepository<EmotionTag, Long> {
    fun findByPaperTradeId(paperTradeId: Long): EmotionTag?
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<EmotionTag>
}
