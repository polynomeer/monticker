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
) {
    fun debit(amount: BigDecimal) {
        require(amount > BigDecimal.ZERO) { "출금 금액은 0보다 커야 합니다" }
        require(cash >= amount) { "잔고 부족: 필요 $amount, 보유 $cash" }
        cash -= amount
        updatedAt = Instant.now()
    }

    fun credit(amount: BigDecimal) {
        require(amount > BigDecimal.ZERO) { "입금 금액은 0보다 커야 합니다" }
        cash += amount
        updatedAt = Instant.now()
    }

    fun reset() {
        cash = BigDecimal("10000000")
        updatedAt = Instant.now()
    }

    fun hasSufficientCash(amount: BigDecimal): Boolean = cash >= amount
}
