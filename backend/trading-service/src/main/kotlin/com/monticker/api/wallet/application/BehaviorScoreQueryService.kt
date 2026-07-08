package com.monticker.api.wallet.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.wallet.domain.BehaviorGrade
import com.monticker.api.wallet.infrastructure.BehaviorScoreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class BehaviorScoreQueryService(
    private val scoreRepo: BehaviorScoreRepository,
    private val objectMapper: ObjectMapper,
) {
    fun findCached(userId: Long, date: LocalDate): BehaviorScoreResponse? {
        val s = scoreRepo.findByUserIdAndScoreDate(userId, date).orElse(null) ?: return null
        val breakdown = s.scoreBreakdown?.let {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
        } ?: emptyMap()
        val feedback = s.feedbackJson?.let {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(it, List::class.java) as List<String>
        } ?: emptyList()
        return BehaviorScoreResponse(
            userId        = userId,
            scoreDate     = date,
            behaviorScore = s.behaviorScore ?: 70,
            survivalScore = s.survivalScore ?: 80,
            grade         = s.grade ?: BehaviorGrade.fromScore(s.behaviorScore ?: 70),
            feedback      = feedback,
            reliabilityNotes = breakdown,
        )
    }
}
