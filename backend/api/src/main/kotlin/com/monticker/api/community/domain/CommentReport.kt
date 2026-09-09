package com.monticker.api.community.domain

import jakarta.persistence.*
import java.time.Instant

/** ADR-037 — 댓글 신고. 저장만 한다 — 자동 숨김/처리 워크플로우는 범위 밖. */
@Entity
@Table(name = "stock_comment_reports")
class CommentReport(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "comment_id", nullable = false)
    val commentId: Long,

    @Column(name = "reporter_id", nullable = false)
    val reporterId: Long,

    @Column(nullable = false, columnDefinition = "TEXT")
    val reason: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
