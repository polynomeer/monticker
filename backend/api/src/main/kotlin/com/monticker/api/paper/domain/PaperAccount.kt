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
    @Column(name = "initial_capital", nullable = false, updatable = false)
    val initialCapital: Money = Money.INITIAL_BALANCE,
    @Convert(converter = MoneyConverter::class)
    @Column(nullable = false) var cash: Money = initialCapital,
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

    /** ADR-089 — 초기화는 이 계좌의 시작 자금으로 되돌린다(기존 계좌는 모두 1,000만원이라 이전 동작과 같다). */
    fun reset() {
        cash = initialCapital
        updatedAt = Instant.now()
    }

    fun hasSufficientCash(amount: Money): Boolean = cash >= amount
}
