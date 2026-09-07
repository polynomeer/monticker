package com.monticker.api.quant.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class ForwardTestStatus { RUNNING, STOPPED }

@Entity
@Table(name = "quant_forward_tests")
class QuantForwardTest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "rule_set_id", nullable = false, length = 24)
    val ruleSetId: String,

    @Column(name = "rule_set_version", nullable = false)
    val ruleSetVersion: Int,

    @Column(name = "stock_id", nullable = false)
    val stockId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ForwardTestStatus = ForwardTestStatus.RUNNING,

    @Column(name = "initial_capital", nullable = false)
    val initialCapital: BigDecimal,

    @Column(nullable = false)
    var cash: BigDecimal,

    @Column(name = "holding_qty", nullable = false)
    var holdingQty: Int = 0,

    @Column(name = "holding_entry_price")
    var holdingEntryPrice: BigDecimal? = null,

    @Column(name = "holding_entry_date")
    var holdingEntryDate: LocalDate? = null,

    @Column(name = "last_evaluated_date")
    var lastEvaluatedDate: LocalDate? = null,

    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),

    @Column(name = "stopped_at")
    var stoppedAt: Instant? = null,
) {
    val isHolding: Boolean get() = holdingQty > 0

    fun openPosition(qty: Int, price: BigDecimal, date: LocalDate) {
        holdingQty = qty
        holdingEntryPrice = price
        holdingEntryDate = date
        cash -= price.multiply(BigDecimal(qty))
    }

    fun closePosition(price: BigDecimal) {
        cash += price.multiply(BigDecimal(holdingQty))
        holdingQty = 0
        holdingEntryPrice = null
        holdingEntryDate = null
    }

    fun stop() {
        require(status == ForwardTestStatus.RUNNING) { "이미 종료된 포워드 테스트입니다: id=$id" }
        status = ForwardTestStatus.STOPPED
        stoppedAt = Instant.now()
    }
}
