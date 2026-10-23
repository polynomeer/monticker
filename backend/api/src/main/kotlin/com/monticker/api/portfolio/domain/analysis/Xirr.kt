package com.monticker.api.portfolio.domain.analysis

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/** 돈의 흐름 한 건 — 내가 낸 돈은 음수, 받은 돈(매도·배당·지금 평가금액)은 양수 */
data class CashFlow(val date: LocalDate, val amount: Double)

/**
 * XIRR(돈 가중 수익률, 연환산) — Σ amount_i / (1 + r)^(days_i / 365) = 0 을 푸는 r.
 * 엑셀 XIRR과 같은 정의다(첫 흐름 날짜 기준, 365일). 뉴턴법으로 풀고, 수렴하지 않으면 이분법으로 다시 푼다.
 *
 * 답이 없으면 null: 흐름의 부호가 한쪽뿐이거나(돈을 넣기만 함), 기간이 [MIN_DAYS]보다 짧다(짧은 기간을 연환산하면 수천 %가 나와
 * 의미가 없다).
 */
object Xirr {
    const val MIN_DAYS = 30L

    fun of(flows: List<CashFlow>): Double? {
        val sorted = flows.filter { it.amount != 0.0 }.sortedBy { it.date }
        if (sorted.size < 2 || sorted.none { it.amount < 0 } || sorted.none { it.amount > 0 }) return null
        val start = sorted.first().date
        if (ChronoUnit.DAYS.between(start, sorted.last().date) < MIN_DAYS) return null
        val t = sorted.map { ChronoUnit.DAYS.between(start, it.date) / 365.0 }
        val a = sorted.map { it.amount }

        fun npv(r: Double) = a.indices.sumOf { a[it] / (1 + r).pow(t[it]) }
        fun dnpv(r: Double) = a.indices.sumOf { -t[it] * a[it] / (1 + r).pow(t[it] + 1) }

        var r = 0.1
        repeat(100) {
            val f = npv(r)
            if (abs(f) < 1e-7) return r
            val d = dnpv(r)
            if (d == 0.0 || !d.isFinite()) return bisect(::npv)
            val next = r - f / d
            if (!next.isFinite() || next <= -0.999999) return bisect(::npv)
            if (abs(next - r) < 1e-10) return next
            r = next
        }
        return bisect(::npv)
    }

    /** -99.9999% ~ 100,000% 사이에서 부호가 바뀌는 곳을 찾는다 */
    private fun bisect(npv: (Double) -> Double): Double? {
        var lo = -0.999999
        var hi = 1000.0
        var flo = npv(lo)
        if (flo * npv(hi) > 0) return null
        repeat(300) {
            val mid = (lo + hi) / 2
            val fm = npv(mid)
            if (abs(fm) < 1e-7 || hi - lo < 1e-12) return mid
            if (flo * fm < 0) hi = mid else { lo = mid; flo = fm }
        }
        return (lo + hi) / 2
    }
}
