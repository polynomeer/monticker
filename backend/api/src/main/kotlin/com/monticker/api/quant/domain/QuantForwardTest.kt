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

    // ADR-078 — 포워드 일치율. 일일 평가(또는 중지) 때마다 같은 기간 재실행과 비교해 갱신한다.
    @Column(name = "match_rate")
    var matchRate: BigDecimal? = null,

    @Column(name = "matched_signals")
    var matchedSignals: Int? = null,

    @Column(name = "compared_signals")
    var comparedSignals: Int? = null,

    @Column(name = "match_evaluated_at")
    var matchEvaluatedAt: Instant? = null,
) {
    val isHolding: Boolean get() = holdingQty > 0

    /** [commission]은 현금에서 함께 뺀다 — 백테스트와 같은 비용 가정(ADR-078). */
    fun openPosition(qty: Int, price: BigDecimal, date: LocalDate, commission: BigDecimal = BigDecimal.ZERO) {
        holdingQty = qty
        holdingEntryPrice = price
        holdingEntryDate = date
        cash -= price.multiply(BigDecimal(qty)) + commission
    }

    fun closePosition(price: BigDecimal, commission: BigDecimal = BigDecimal.ZERO) {
        cash += price.multiply(BigDecimal(holdingQty)) - commission
        holdingQty = 0
        holdingEntryPrice = null
        holdingEntryDate = null
    }

    fun recordMatch(rate: Double?, matched: Int, compared: Int, at: Instant = Instant.now()) {
        matchRate = rate?.let { BigDecimal.valueOf(it).setScale(4, java.math.RoundingMode.HALF_UP) }
        matchedSignals = matched
        comparedSignals = compared
        matchEvaluatedAt = at
    }

    fun stop() {
        require(status == ForwardTestStatus.RUNNING) { "이미 종료된 포워드 테스트입니다: id=$id" }
        status = ForwardTestStatus.STOPPED
        stoppedAt = Instant.now()
    }
}
