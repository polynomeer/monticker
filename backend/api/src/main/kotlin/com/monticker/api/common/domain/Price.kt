package com.monticker.api.common.domain

import jakarta.persistence.Converter
import jakarta.persistence.AttributeConverter
import java.math.BigDecimal
import java.math.RoundingMode

data class Price(val amount: BigDecimal) : Comparable<Price> {

    init {
        require(amount > BigDecimal.ZERO) { "가격은 0보다 커야 합니다: $amount" }
    }

    fun toMoney(qty: Int): Money = Money(amount.multiply(BigDecimal(qty)))

    override fun compareTo(other: Price) = amount.compareTo(other.amount)

    override fun toString() = amount.toPlainString()

    companion object {
        fun of(amount: BigDecimal) = Price(amount)
        fun of(amount: String) = Price(BigDecimal(amount))
    }
}

@Converter
class PriceConverter : AttributeConverter<Price?, BigDecimal?> {
    override fun convertToDatabaseColumn(price: Price?) = price?.amount
    override fun convertToEntityAttribute(col: BigDecimal?) = col?.let { Price(it) }
}
