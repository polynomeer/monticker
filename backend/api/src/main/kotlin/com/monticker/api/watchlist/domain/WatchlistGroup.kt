package com.monticker.api.watchlist.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "watchlist_groups")
class WatchlistGroup(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(nullable = false)
    var sortOrder: Int = 0,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    // 그룹 안 순서 — sort_order가 같은 예전 행은 추가 순(id)
    @OrderBy("sortOrder ASC, id ASC")
    @OneToMany(mappedBy = "group", cascade = [CascadeType.ALL], orphanRemoval = true)
    val items: MutableList<WatchlistItem> = mutableListOf(),
)
