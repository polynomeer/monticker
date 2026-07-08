package com.monticker.api.common.domain

import jakarta.persistence.Converter
import jakarta.persistence.AttributeConverter
import java.math.BigDecimal
import java.math.RoundingMode

data class Money(val amount: BigDecimal) : Comparable<Money> {

    init {
        require(amount >= BigDecimal.ZERO) { "금액은 0 이상이어야 합니다: $amount" }
    }

    operator fun plus(other: Money) = Money(amount + other.amount)
    operator fun minus(other: Money) = Money((amount - other.amount).also {
        require(it >= BigDecimal.ZERO) { "잔액 부족: $amount - ${other.amount}" }
    })
    operator fun times(qty: Int) = Money(amount.multiply(BigDecimal(qty)))
    fun negate(): Money = Money(amount.negate().also {
        require(it >= BigDecimal.ZERO) { "Money는 음수를 가질 수 없습니다" }
    })

    override fun compareTo(other: Money) = amount.compareTo(other.amount)

    override fun toString() = amount.toPlainString()

    companion object {
        val ZERO = Money(BigDecimal.ZERO)
        val INITIAL_BALANCE = Money(BigDecimal("10000000"))
        fun of(amount: BigDecimal) = Money(amount)
        fun of(amount: String) = Money(BigDecimal(amount))
    }
}

@Converter
class MoneyConverter : AttributeConverter<Money?, BigDecimal?> {
    override fun convertToDatabaseColumn(money: Money?) = money?.amount
    override fun convertToEntityAttribute(col: BigDecimal?) = col?.let { Money(it) }
}
