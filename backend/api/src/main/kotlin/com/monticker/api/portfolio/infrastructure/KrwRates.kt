package com.monticker.api.portfolio.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** 원화 환율 한 벌 — 지금 값과 거래일별 종가. [mocked]면 화면이 "모의 환율"로 표시한다(ADR-071) */
class KrwRateBook(
    val current: Map<String, CurrentRate>,
    private val daily: Map<String, TreeMap<LocalDate, BigDecimal>>,
    /** 쓴 일별 값 중 모의 값이 섞였는가 */
    val dailyMocked: Boolean,
) {
    data class CurrentRate(val rate: BigDecimal, val asOf: Instant?, val mocked: Boolean)

    fun now(currency: String): BigDecimal? = if (currency == KRW) BigDecimal.ONE else current[currency]?.rate

    /** 그 날(없으면 그 전 마지막 거래일)의 종가. 이력보다 이른 날이면 null — 지어내지 않는다 */
    fun on(currency: String, date: LocalDate): BigDecimal? =
        if (currency == KRW) BigDecimal.ONE else daily[currency]?.floorEntry(date)?.value

    val mocked: Boolean get() = dailyMocked || current.values.any { it.mocked }

    companion object { const val KRW = "KRW" }
}

/**
 * 원화 환율 읽기(ADR-111) — worker의 지수·환율 수집(ADR-071)이 쓰는 market_index_quotes·market_index_daily를 읽기만 한다.
 * 지금 수집하는 환율은 원/달러(USDKRW)뿐이라 USD만 바꾼다. 그 밖의 통화는 환산하지 않는다(합계에서 빠지고 화면이 밝힌다).
 */
@Component
class KrwRates(private val jdbc: JdbcTemplate) {

    fun load(): KrwRateBook {
        val current = jdbc.query(
            "SELECT code, value, as_of, is_mocked FROM market_index_quotes WHERE code IN (${CODES.keys.joinToString(",") { "?" }})",
            { rs, _ ->
                CODES.getValue(rs.getString("code")) to
                    KrwRateBook.CurrentRate(rs.getBigDecimal("value"), rs.getTimestamp("as_of")?.toInstant(), rs.getBoolean("is_mocked"))
            },
            *CODES.keys.toTypedArray(),
        ).toMap()
        var mocked = false
        val daily = HashMap<String, TreeMap<LocalDate, BigDecimal>>()
        jdbc.query(
            "SELECT code, trade_date, close, is_mocked FROM market_index_daily WHERE code IN (${CODES.keys.joinToString(",") { "?" }})",
            { rs ->
                daily.getOrPut(CODES.getValue(rs.getString("code"))) { TreeMap() }[rs.getDate("trade_date").toLocalDate()] = rs.getBigDecimal("close")
                if (rs.getBoolean("is_mocked")) mocked = true
            },
            *CODES.keys.toTypedArray(),
        )
        return KrwRateBook(current, daily, mocked)
    }

    companion object {
        /** 지수 코드 → 통화 */
        val CODES = mapOf("USDKRW" to "USD")
    }
}
