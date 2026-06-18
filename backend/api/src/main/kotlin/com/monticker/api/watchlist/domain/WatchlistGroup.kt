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

    @OneToMany(mappedBy = "group", cascade = [CascadeType.ALL], orphanRemoval = true)
    val items: MutableList<WatchlistItem> = mutableListOf(),
)
