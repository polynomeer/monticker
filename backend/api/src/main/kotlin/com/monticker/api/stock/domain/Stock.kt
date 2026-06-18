package com.monticker.api.stock.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.Instant

@Entity
@Table(name = "stocks")
class Stock(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 20)
    val symbol: String,

    @Column(nullable = false, length = 200)
    val name: String,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val market: Market,

    @Column(nullable = false, length = 50)
    val exchange: String,

    @Column(length = 100)
    val sector: String? = null,

    @Column(length = 100)
    val industry: String? = null,

    @Column(nullable = false, length = 10)
    val country: String = "KR",

    @Column(nullable = false, length = 10)
    val currency: String = "KRW",

    val listedAt: LocalDate? = null,
    val delistedAt: LocalDate? = null,

    @Column(nullable = false)
    val isActive: Boolean = true,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)

enum class Market {
    KOSPI, KOSDAQ, NASDAQ, NYSE
}
