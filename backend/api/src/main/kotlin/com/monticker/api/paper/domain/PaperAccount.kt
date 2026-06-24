package com.monticker.api.paper.domain
import jakarta.persistence.*
import java.math.BigDecimal; import java.time.Instant

@Entity @Table(name = "paper_accounts")
class PaperAccount(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "user_id", nullable = false, unique = true) val userId: Long,
    @Column(nullable = false) var cash: BigDecimal = BigDecimal("10000000"),
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
)
