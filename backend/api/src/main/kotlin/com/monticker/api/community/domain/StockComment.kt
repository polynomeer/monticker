package com.monticker.api.community.domain

import jakarta.persistence.*
import java.time.Instant

/** ADR-037 — 종목 커뮤니티 댓글. 종목 전체에 자유롭게 쓰되, 특정 이벤트를 선택적으로 태그할 수 있다. */
@Entity
@Table(name = "stock_comments")
class StockComment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(name = "event_id")
    val eventId: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    fun delete() {
        deletedAt = Instant.now()
    }
}
