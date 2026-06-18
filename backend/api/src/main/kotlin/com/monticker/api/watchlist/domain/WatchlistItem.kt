package com.monticker.api.watchlist.domain

import com.monticker.api.stock.domain.Stock
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "watchlist_items")
class WatchlistItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    val group: WatchlistGroup,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    val stock: Stock,

    var memo: String? = null,
    var targetPrice: BigDecimal? = null,

    @Column(nullable = false)
    var sortOrder: Int = 0,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)
