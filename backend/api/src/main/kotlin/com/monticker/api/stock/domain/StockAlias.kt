package com.monticker.api.stock.domain

import jakarta.persistence.*

@Entity
@Table(name = "stock_aliases")
class StockAlias(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    val stock: Stock,

    @Column(nullable = false, length = 200)
    val alias: String,

    @Column(nullable = false, length = 50)
    val aliasType: String,
)
