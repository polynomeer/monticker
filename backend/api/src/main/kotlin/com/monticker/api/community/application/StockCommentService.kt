package com.monticker.api.community.application

import com.monticker.api.community.domain.CommentReport
import com.monticker.api.community.domain.StockComment
import com.monticker.api.community.infrastructure.CommentReportRepository
import com.monticker.api.community.infrastructure.StockCommentRepository
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.stock.application.StockService
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant

data class StockCommentResponse(
    val id: Long,
    val stockId: Long,
    val eventId: Long?,
    val userId: Long,
    val authorNickname: String,
    val content: String,
    val createdAt: Instant,
)

/** ADR-037 — 종목 커뮤니티 댓글. 작성 시 CommentModerationService의 매수·매도 권유 필터를 거친다. */
@Service
class StockCommentService(
    private val commentRepository: StockCommentRepository,
    private val reportRepository: CommentReportRepository,
    private val stockService: StockService,
    private val eventTimelineService: EventTimelineService,
    private val moderationService: CommentModerationService,
    private val jdbc: JdbcTemplate,
) {
    fun create(userId: Long, stockId: Long, content: String, eventId: Long?): StockCommentResponse {
        require(content.isNotBlank()) { "댓글 내용을 입력해주세요." }
        stockService.getById(stockId)
        if (eventId != null) {
            val event = eventTimelineService.getById(eventId)
            require(event.stockId == stockId) { "이 종목의 이벤트가 아닙니다." }
        }

        when (val result = moderationService.check(content)) {
            is ModerationResult.Blocked -> throw IllegalArgumentException(result.reason)
            ModerationResult.Allowed -> {}
        }

        val saved = commentRepository.save(
            StockComment(stockId = stockId, eventId = eventId, userId = userId, content = content)
        )
        return saved.toResponse(nicknameOf(userId))
    }

    fun delete(userId: Long, commentId: Long) {
        val comment = commentRepository.findById(commentId)
            .orElseThrow { NoSuchElementException("댓글을 찾을 수 없습니다: $commentId") }
        require(comment.userId == userId) { "본인 댓글만 삭제할 수 있습니다." }
        comment.delete()
        commentRepository.save(comment)
    }

    fun report(userId: Long, commentId: Long, reason: String) {
        require(reason.isNotBlank()) { "신고 사유를 입력해주세요." }
        commentRepository.findById(commentId)
            .orElseThrow { NoSuchElementException("댓글을 찾을 수 없습니다: $commentId") }
        require(!reportRepository.existsByCommentIdAndReporterId(commentId, userId)) { "이미 신고한 댓글입니다." }
        reportRepository.save(CommentReport(commentId = commentId, reporterId = userId, reason = reason))
    }

    fun list(stockId: Long, eventId: Long?, pageable: Pageable): List<StockCommentResponse> {
        val comments = if (eventId != null)
            commentRepository.findByStockIdAndEventIdAndDeletedAtIsNullOrderByCreatedAtDesc(stockId, eventId, pageable)
        else
            commentRepository.findByStockIdAndDeletedAtIsNullOrderByCreatedAtDesc(stockId, pageable)

        if (comments.isEmpty()) return emptyList()
        val nicknames = nicknamesOf(comments.map { it.userId }.distinct())
        return comments.map { it.toResponse(nicknames[it.userId] ?: "알 수 없음") }
    }

    private fun nicknameOf(userId: Long): String = nicknamesOf(listOf(userId))[userId] ?: "알 수 없음"

    private fun nicknamesOf(userIds: List<Long>): Map<Long, String> {
        if (userIds.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, nickname FROM users WHERE id IN (${userIds.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("id") to rs.getString("nickname") },
            *userIds.toTypedArray(),
        ).toMap()
    }

    private fun StockComment.toResponse(authorNickname: String) = StockCommentResponse(
        id = id, stockId = stockId, eventId = eventId, userId = userId,
        authorNickname = authorNickname, content = content, createdAt = createdAt,
    )
}
