package com.monticker.api.community.infrastructure

import com.monticker.api.community.domain.CommentReport
import org.springframework.data.jpa.repository.JpaRepository

interface CommentReportRepository : JpaRepository<CommentReport, Long> {
    fun existsByCommentIdAndReporterId(commentId: Long, reporterId: Long): Boolean
}
