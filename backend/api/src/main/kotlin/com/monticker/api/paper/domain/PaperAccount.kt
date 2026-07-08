package com.monticker.api.paper.domain

import com.monticker.api.common.domain.Money
import com.monticker.api.common.domain.MoneyConverter
import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "paper_accounts")
class PaperAccount(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "user_id", nullable = false, unique = true) val userId: Long,
    @Convert(converter = MoneyConverter::class)
    @Column(nullable = false) var cash: Money = Money.INITIAL_BALANCE,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
) {
    fun debit(amount: Money) {
        cash = cash - amount
        updatedAt = Instant.now()
    }

    fun credit(amount: Money) {
        cash = cash + amount
        updatedAt = Instant.now()
    }

    fun reset() {
        cash = Money.INITIAL_BALANCE
        updatedAt = Instant.now()
    }

    fun hasSufficientCash(amount: Money): Boolean = cash >= amount
}
