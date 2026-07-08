package com.monticker.api.paper.domain
import jakarta.persistence.*
import java.math.BigDecimal; import java.time.Instant

@Entity @Table(name = "paper_trades")
class PaperTrade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "stock_id", nullable = false) val stockId: Long,
    @Column(nullable = false) val side: String,
    @Column(nullable = false) val quantity: Int,
    @Column(nullable = false) val price: BigDecimal,
    @Column(nullable = false) val amount: BigDecimal,
    @Column(name = "traded_at", nullable = false) val tradedAt: Instant = Instant.now(),
)
