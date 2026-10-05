package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.MarketIndexClose
import com.monticker.api.marketdata.domain.MarketIndexQuote
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.time.LocalDate

/** ADR-071 — worker(MarketIndexCollector)가 쓴 지수·환율 테이블을 읽기만 한다. */
@Repository
class MarketIndexRepository(private val jdbc: JdbcTemplate) {

    fun findQuotes(): List<MarketIndexQuote> = jdbc.query(
        "SELECT code, name, value, prev_close, as_of, source, is_mocked FROM market_index_quotes",
    ) { rs, _ ->
        MarketIndexQuote(
            code      = rs.getString("code"),
            name      = rs.getString("name"),
            value     = rs.getBigDecimal("value"),
            prevClose = rs.getBigDecimal("prev_close"),
            asOf      = rs.getTimestamp("as_of").toInstant(),
            source    = rs.getString("source"),
            isMocked  = rs.getBoolean("is_mocked"),
        )
    }

    /** 기간 안의 최근 [limit]개 일별 종가(오래된 날짜 → 최근). */
    fun findCloses(code: String, from: LocalDate, to: LocalDate, limit: Int): List<MarketIndexClose> = jdbc.query(
        """
        SELECT trade_date, close, is_mocked FROM (
            SELECT trade_date, close, is_mocked FROM market_index_daily
            WHERE code = ? AND trade_date BETWEEN ? AND ?
            ORDER BY trade_date DESC
            LIMIT ?
        ) t ORDER BY trade_date ASC
        """.trimIndent(),
        { rs, _ -> MarketIndexClose(rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("close"), rs.getBoolean("is_mocked")) },
        code, Date.valueOf(from), Date.valueOf(to), limit,
    )
}
