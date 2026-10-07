package com.monticker.api.common.calendar

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** ADR-086 — market_holidays / market_calendar_years 읽기 전용. 쓰기는 Flyway 마이그레이션으로만 한다. */
@Repository
class MarketHolidayRepository(private val jdbc: JdbcTemplate) {

    fun findHolidays(market: String = KRX): List<MarketHoliday> =
        jdbc.query(
            "SELECT holiday_date, name, source FROM market_holidays WHERE market = ? ORDER BY holiday_date",
            { rs, _ -> MarketHoliday(rs.getDate("holiday_date").toLocalDate(), rs.getString("name"), rs.getString("source")) },
            market,
        )

    fun findCoveredYears(market: String = KRX): Set<Int> =
        jdbc.queryForList("SELECT year FROM market_calendar_years WHERE market = ?", Int::class.java, market).toSet()

    companion object {
        const val KRX = "KRX"
    }
}
