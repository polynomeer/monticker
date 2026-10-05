package com.monticker.api.risk.domain

import org.springframework.modulith.NamedInterface
import java.math.BigDecimal

/**
 * ADR-069 — 사용자가 바꿀 수 있는 한도 항목. [key]는 API/화면의 필드 이름이고 DB(`risk_limit_pending_changes.field`)에는 enum 이름이 들어간다.
 *
 * 모든 값은 BigDecimal 하나로 다룬다(정수 항목은 정수 BigDecimal, IS_ACTIVE는 1/0, 섹터 한도 해제는 null).
 * [looseness]가 클수록 위험을 더 허용한다 — 완화(지연 적용)와 강화(즉시 적용)를 이 값 하나로 가른다.
 */
@NamedInterface("api")
enum class RiskLimitField(val key: String, val min: BigDecimal?, val max: BigDecimal?, val integer: Boolean = false) {
    DAILY_LOSS_LIMIT_PCT("dailyLossLimitPct", BigDecimal("0.01"), BigDecimal("100")),
    CONCENTRATION_LIMIT_PCT("concentrationLimitPct", BigDecimal("0.01"), BigDecimal("100")),
    VAR_LIMIT_PCT("varLimitPct", BigDecimal("0.01"), BigDecimal("100")),
    MAX_POSITION_COUNT("maxPositionCount", BigDecimal.ONE, BigDecimal("1000"), integer = true),
    MAX_HOURLY_ORDERS("maxHourlyOrders", BigDecimal.ONE, BigDecimal("1000"), integer = true),
    SECTOR_CONCENTRATION_LIMIT_PCT("sectorConcentrationLimitPct", BigDecimal("0.01"), BigDecimal("100")),
    IS_ACTIVE("isActive", null, null);

    fun get(l: RiskLimit): BigDecimal? = when (this) {
        DAILY_LOSS_LIMIT_PCT -> l.dailyLossLimitPct
        CONCENTRATION_LIMIT_PCT -> l.concentrationLimitPct
        VAR_LIMIT_PCT -> l.varLimitPct
        MAX_POSITION_COUNT -> BigDecimal(l.maxPositionCount)
        MAX_HOURLY_ORDERS -> BigDecimal(l.maxHourlyOrders)
        SECTOR_CONCENTRATION_LIMIT_PCT -> l.sectorConcentrationLimitPct
        IS_ACTIVE -> if (l.isActive) BigDecimal.ONE else BigDecimal.ZERO
    }

    fun set(l: RiskLimit, v: BigDecimal?) {
        when (this) {
            DAILY_LOSS_LIMIT_PCT -> l.dailyLossLimitPct = v!!
            CONCENTRATION_LIMIT_PCT -> l.concentrationLimitPct = v!!
            VAR_LIMIT_PCT -> l.varLimitPct = v!!
            MAX_POSITION_COUNT -> l.maxPositionCount = v!!.intValueExact()
            MAX_HOURLY_ORDERS -> l.maxHourlyOrders = v!!.intValueExact()
            SECTOR_CONCENTRATION_LIMIT_PCT -> l.sectorConcentrationLimitPct = v
            IS_ACTIVE -> l.isActive = v!!.signum() != 0
        }
    }

    /** 클수록 느슨하다. 섹터 한도 해제(null)는 무한대, 리스크 체크 끔(0)은 켬(1)보다 느슨하다. */
    fun looseness(v: BigDecimal?): BigDecimal = when {
        this == IS_ACTIVE -> BigDecimal.ONE - (v ?: BigDecimal.ONE)
        v == null -> UNBOUNDED
        else -> v
    }

    /** 잘못된 값은 IllegalArgumentException(400). null은 섹터 한도에만 허용된다(해제). */
    fun validate(v: BigDecimal?) {
        if (v == null) {
            require(this == SECTOR_CONCENTRATION_LIMIT_PCT) { "$key 값이 필요합니다." }
            return
        }
        if (this == IS_ACTIVE) {
            require(v.compareTo(BigDecimal.ZERO) == 0 || v.compareTo(BigDecimal.ONE) == 0) { "isActive는 true/false여야 합니다." }
            return
        }
        if (integer) require(v.stripTrailingZeros().scale() <= 0) { "$key 는 정수여야 합니다." }
        else require(v.stripTrailingZeros().scale() <= 2) { "$key 는 소수 둘째 자리까지 입력할 수 있습니다." }
        val lo = min!!
        val hi = max!!
        require(v >= lo && v <= hi) { "$key 는 ${lo.toPlainString()} 이상 ${hi.toPlainString()} 이하여야 합니다." }
    }

    companion object {
        private val UNBOUNDED = BigDecimal("1000000")
        fun ofKey(key: String): RiskLimitField? = entries.firstOrNull { it.key == key }
    }
}
